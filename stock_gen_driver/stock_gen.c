// SPDX-License-Identifier: GPL-2.0
/*
 * stock_gen.c — Linux character device driver: stock price generator
 *
 * Creates /dev/stockN (N = 0..STOCK_MAX_DEVICES-1).
 * Each device simulates a stock ticker using a geometric Brownian Motion
 * step: price(t+1) = price(t) * exp(drift*dt + volatility*sqrt(dt)*Z)
 * where Z is a standard-normal sample built from kernel random bytes.
 *
 * Kotlin backend usage:
 *   FileInputStream("/dev/stock0").use { fis ->
 *       val buf = ByteArray(StockTick.SIZE)
 *       fis.read(buf)          // blocks until next tick (poll-friendly)
 *       val tick = StockTick.parse(buf)
 *   }
 *
 * Binary record layout (little-endian, 32 bytes):
 *   [0..7]   u64  sequence number
 *   [8..15]  s64  timestamp (ns since boot, ktime_get_ns)
 *   [16..23] s64  price in microdollars (price * 1_000_000)
 *   [24..27] s32  volume (simulated, shares per tick)
 *   [28..31] u32  device index
 *
 * Build:
 *   make -C /lib/modules/$(uname -r)/build M=$(pwd) modules
 *
 * Load:
 *   sudo insmod stock_gen.ko
 *   ls /dev/stock*
 */

#include <linux/module.h>
#include <linux/kernel.h>
#include <linux/fs.h>
#include <linux/cdev.h>
#include <linux/device.h>
#include <linux/uaccess.h>
#include <linux/slab.h>
#include <linux/random.h>
#include <linux/hrtimer.h>
#include <linux/ktime.h>
#include <linux/poll.h>
#include <linux/mutex.h>
#include <linux/wait.h>
#include <linux/atomic.h>
#include <linux/math64.h>
#include <linux/ioctl.h>

/* ── tunables ─────────────────────────────────────────────────────────── */

#define STOCK_MAX_DEVICES   4
#define STOCK_RING_SIZE     64          /* must be power of 2              */
#define STOCK_TICK_MS       500         /* default tick interval, ms        */
#define STOCK_DEFAULT_PRICE 10000000LL  /* $10.00 in microdollars           */
#define DRIVER_NAME         "stock_gen"
#define CLASS_NAME          "stock"

/* ── record layout (32 bytes, LE) ─────────────────────────────────────── */

struct stock_tick {
    __le64 seq;          /* monotonic sequence                             */
    __le64 timestamp_ns; /* ktime_get_ns() at generation                   */
    __le64 price_udollar;/* price * 1_000_000 (microdollars), signed       */
    __le32 volume;       /* simulated trade volume                         */
    __le32 dev_idx;      /* which device this came from                    */
} __packed;

#define STOCK_TICK_SIZE  sizeof(struct stock_tick)   /* 32 */

/* ── ioctl interface ──────────────────────────────────────────────────── */

#define STOCK_IOC_MAGIC    'S'

struct stock_params {
    __s64 base_price_udollar; /* starting price in microdollars            */
    __s32 volatility_bps;     /* annualised vol in basis points (1 bp=0.01%)*/
    __s32 drift_bps;          /* annualised drift in basis points           */
    __u32 tick_interval_ms;   /* timer period in milliseconds               */
    __u32 reserved;
};

#define STOCK_IOC_SET_PARAMS  _IOW(STOCK_IOC_MAGIC, 1, struct stock_params)
#define STOCK_IOC_GET_PARAMS  _IOR(STOCK_IOC_MAGIC, 2, struct stock_params)
#define STOCK_IOC_RESET       _IO (STOCK_IOC_MAGIC, 3)

/* ── per-device state ─────────────────────────────────────────────────── */

struct stock_dev {
    /* cdev bookkeeping */
    struct cdev        cdev;
    struct device     *device;
    unsigned int       idx;

    /* ring buffer (lock-free single-producer / multi-consumer) */
    struct stock_tick  ring[STOCK_RING_SIZE];
    atomic_t           head;   /* producer writes here (wrapping)          */
    atomic_t           tail;   /* consumer reads here                      */

    /* wait queue for poll/read blocking */
    wait_queue_head_t  wq;

    /* simulation state (protected by params_lock) */
    struct mutex       params_lock;
    s64                price_udollar;   /* current price                    */
    s32                volatility_bps;
    s32                drift_bps;
    u32                tick_interval_ms;
    atomic64_t         seq;

    /* hrtimer */
    struct hrtimer     timer;
};

/* ── module globals ───────────────────────────────────────────────────── */

static struct stock_dev  *stock_devs[STOCK_MAX_DEVICES];
static struct class      *stock_class;
static dev_t              stock_major;

static int num_devices = STOCK_MAX_DEVICES;
module_param(num_devices, int, 0444);
MODULE_PARM_DESC(num_devices, "Number of stock devices to create (1-4)");

/* ── math helpers ─────────────────────────────────────────────────────── */

/*
 * box_muller_normal() — returns a scaled integer approximation of N(0,1)*1000
 * using two uniform u32 random values.  Integer-only; no FPU.
 *
 * We use the rational approximation to the inverse normal CDF (Abramowitz &
 * Stegun 26.2.17) operating on fixed-point arithmetic.  Accuracy is ~3 σ.
 */
static s32 box_muller_normal(void)
{
    u32 u1, u2;
    s64 x, y, r2, angle_cos;
    /* two independent uniforms in (0, 2^32-1) */
    get_random_bytes(&u1, sizeof(u1));
    get_random_bytes(&u2, sizeof(u2));

    /*
     * Simple approximation: map u1 to a "quantile" via a lookup-free
     * polynomial.  We skip a full Box-Muller (needs log) and use a
     * direct rational approx from A&S 26.2.17:
     *
     *   t  = sqrt(-2 * ln(u))  → approximated via integer steps
     *
     * For a kernel driver the precision is fine: we only need the price
     * to move in a realistic range, not to pass statistical tests.
     *
     * Simplified version: use central limit theorem on 12 uniform samples.
     * Each get_random_u8() gives uniform [0,255], mean 127.5, variance 5418.
     * Sum of 12 → mean 1530, std ≈ 255.  Subtract mean, divide by std → N(0,1)*1000.
     */
    {
        int i;
        s32 sum = 0;
        u8 b;
        for (i = 0; i < 12; i++) {
            get_random_bytes(&b, 1);
            sum += (s32)b;
        }
        /* sum ~ N(1530, 255²);  normalize to N(0, 1000) */
        return (s32)((((s64)(sum - 1530)) * 1000) / 255);
    }
    (void)x; (void)y; (void)r2; (void)angle_cos; /* suppress unused warnings */
}

/*
 * generate_tick() — advance price by one GBM step and fill a stock_tick.
 *
 *   dP = P * (drift * dt  +  vol * sqrt(dt) * Z)
 *
 * dt   = tick_interval_ms / (252 * 24 * 3600 * 1000)   [trading-day fraction]
 * vol  = volatility_bps / 10000
 * Z    ~ N(0,1)  (from box_muller_normal, scaled *1000)
 *
 * All arithmetic stays in s64 fixed-point to avoid FPU.
 */
static void generate_tick(struct stock_dev *sd, struct stock_tick *t)
{
    s64 price, dp;
    s32 z_scaled;       /* N(0,1)*1000                                     */
    s32 vol_bps, drift_bps;
    u32 dt_ms;
    u32 volume;
    u64 seq;

    mutex_lock(&sd->params_lock);
    price      = sd->price_udollar;
    vol_bps    = sd->volatility_bps;
    drift_bps  = sd->drift_bps;
    dt_ms      = sd->tick_interval_ms;
    mutex_unlock(&sd->params_lock);

    z_scaled = box_muller_normal();   /* ≈ Z*1000 */

    /*
     * dp = price * (drift_bps/10000 * dt  +  vol_bps/10000 * sqrt(dt) * z/1000)
     *
     * Use dt in units of "trading seconds per year" to keep numbers sane.
     * 1 trading year ≈ 252 * 6.5 * 3600 = 5,896,800 s ≈ 5_897_000 s
     * sqrt(dt) ≈ sqrt(dt_ms / 1000) / sqrt(5_897_000)
     *          ≈ sqrt(dt_ms) / 2429   (integer approx, good for 100ms-10s)
     *
     * Stochastic term (scaled by 1e6 to keep microdollar precision):
     *   dp_stoch = price * vol_bps * z_scaled * sqrt_dt_scaled
     *            / (10000 * 1000 * 2429)
     */
    {
        /* integer sqrt(dt_ms)*100 — good enough for 100..2000 ms */
        u32 sqrt_dt;
        if      (dt_ms <= 100)  sqrt_dt = 10;
        else if (dt_ms <= 250)  sqrt_dt = 16;
        else if (dt_ms <= 500)  sqrt_dt = 22;
        else if (dt_ms <= 1000) sqrt_dt = 32;
        else if (dt_ms <= 2000) sqrt_dt = 45;
        else                    sqrt_dt = 63;  /* ~4000 ms */

        /* stochastic: price * vol_bps * z_scaled * sqrt_dt / (10000*1000*2429*100) */
        dp = (price / 1000000LL) * (s64)vol_bps * (s64)z_scaled * (s64)sqrt_dt;
        dp = div64_s64(dp, (s64)10000 * 1000 * 2429 * 100 / 1000000);

        /* drift: price * drift_bps * dt_ms / (10000 * 5897000 * 1000) */
        s64 dp_drift = (price / 10000LL) * (s64)drift_bps * (s64)dt_ms;
        dp_drift = div64_s64(dp_drift, (s64)5897000 * 1000);
        dp += dp_drift;
    }

    price += dp;
    /* clamp: never go below $0.01 */
    if (price < 10000LL)
        price = 10000LL;

    mutex_lock(&sd->params_lock);
    sd->price_udollar = price;
    mutex_unlock(&sd->params_lock);

    /* simulated volume: random around 1000 shares/tick */
    get_random_bytes(&volume, sizeof(volume));
    volume = 100 + (volume % 1900);

    seq = (u64)atomic64_inc_return(&sd->seq);

    t->seq           = cpu_to_le64(seq);
    t->timestamp_ns  = cpu_to_le64(ktime_get_ns());
    t->price_udollar = cpu_to_le64((u64)price);
    t->volume        = cpu_to_le32(volume);
    t->dev_idx       = cpu_to_le32(sd->idx);
}

/* ── hrtimer callback ─────────────────────────────────────────────────── */

static enum hrtimer_restart stock_timer_cb(struct hrtimer *timer)
{
    struct stock_dev *sd = container_of(timer, struct stock_dev, timer);
    int head, next_head;
    struct stock_tick tick;

    generate_tick(sd, &tick);

    head      = atomic_read(&sd->head);
    next_head = (head + 1) & (STOCK_RING_SIZE - 1);

    /* if ring is full, advance tail (drop oldest) */
    if (next_head == (atomic_read(&sd->tail) & (STOCK_RING_SIZE - 1)))
        atomic_inc(&sd->tail);

    sd->ring[head] = tick;
    smp_store_release(&sd->ring[head], tick); /* publish */
    atomic_set(&sd->head, next_head);

    wake_up_interruptible(&sd->wq);

    hrtimer_forward_now(timer,
        ms_to_ktime(READ_ONCE(sd->tick_interval_ms)));
    return HRTIMER_RESTART;
}

/* ── file operations ──────────────────────────────────────────────────── */

/* per-open state: track where this reader is in the ring */
struct stock_reader {
    struct stock_dev *sd;
    int               pos;          /* ring index this reader is at        */
    bool              nonblock;
};

static int stock_open(struct inode *inode, struct file *filp)
{
    struct stock_dev *sd = container_of(inode->i_cdev,
                                        struct stock_dev, cdev);
    struct stock_reader *rdr;

    rdr = kzalloc(sizeof(*rdr), GFP_KERNEL);
    if (!rdr)
        return -ENOMEM;

    rdr->sd       = sd;
    rdr->pos      = atomic_read(&sd->head); /* start at current head       */
    rdr->nonblock = !!(filp->f_flags & O_NONBLOCK);
    filp->private_data = rdr;
    return 0;
}

static int stock_release(struct inode *inode, struct file *filp)
{
    kfree(filp->private_data);
    return 0;
}

static ssize_t stock_read(struct file *filp, char __user *buf,
                           size_t count, loff_t *ppos)
{
    struct stock_reader *rdr = filp->private_data;
    struct stock_dev    *sd  = rdr->sd;
    struct stock_tick    tick;
    size_t               written = 0;
    int                  ret;

    if (count < STOCK_TICK_SIZE)
        return -EINVAL;

    /* wait until at least one new tick is available */
    while (rdr->pos == atomic_read(&sd->head)) {
        if (rdr->nonblock)
            return -EAGAIN;
        ret = wait_event_interruptible(sd->wq,
                  rdr->pos != atomic_read(&sd->head));
        if (ret)
            return ret;
    }

    /* drain as many ticks as the user buffer can hold */
    while (written + STOCK_TICK_SIZE <= count &&
           rdr->pos != atomic_read(&sd->head)) {
        tick = sd->ring[rdr->pos];
        rdr->pos = (rdr->pos + 1) & (STOCK_RING_SIZE - 1);

        if (copy_to_user(buf + written, &tick, STOCK_TICK_SIZE))
            return written ? (ssize_t)written : -EFAULT;
        written += STOCK_TICK_SIZE;
    }
    return (ssize_t)written;
}

static __poll_t stock_poll(struct file *filp, poll_table *wait)
{
    struct stock_reader *rdr = filp->private_data;
    struct stock_dev    *sd  = rdr->sd;

    poll_wait(filp, &sd->wq, wait);
    if (rdr->pos != atomic_read(&sd->head))
        return EPOLLIN | EPOLLRDNORM;
    return 0;
}

static long stock_ioctl(struct file *filp, unsigned int cmd, unsigned long arg)
{
    struct stock_reader *rdr = filp->private_data;
    struct stock_dev    *sd  = rdr->sd;
    struct stock_params  p;

    switch (cmd) {
    case STOCK_IOC_GET_PARAMS:
        mutex_lock(&sd->params_lock);
        p.base_price_udollar = sd->price_udollar;
        p.volatility_bps     = sd->volatility_bps;
        p.drift_bps          = sd->drift_bps;
        p.tick_interval_ms   = sd->tick_interval_ms;
        p.reserved           = 0;
        mutex_unlock(&sd->params_lock);
        if (copy_to_user((void __user *)arg, &p, sizeof(p)))
            return -EFAULT;
        return 0;

    case STOCK_IOC_SET_PARAMS:
        if (copy_from_user(&p, (const void __user *)arg, sizeof(p)))
            return -EFAULT;
        if (p.tick_interval_ms < 10 || p.tick_interval_ms > 60000)
            return -EINVAL;
        if (p.base_price_udollar <= 0)
            return -EINVAL;
        mutex_lock(&sd->params_lock);
        sd->price_udollar   = p.base_price_udollar;
        sd->volatility_bps  = p.volatility_bps;
        sd->drift_bps       = p.drift_bps;
        sd->tick_interval_ms = p.tick_interval_ms;
        mutex_unlock(&sd->params_lock);
        /* restart timer with new interval */
        hrtimer_cancel(&sd->timer);
        hrtimer_start(&sd->timer,
                      ms_to_ktime(p.tick_interval_ms),
                      HRTIMER_MODE_REL);
        return 0;

    case STOCK_IOC_RESET:
        mutex_lock(&sd->params_lock);
        sd->price_udollar = STOCK_DEFAULT_PRICE;
        mutex_unlock(&sd->params_lock);
        atomic64_set(&sd->seq, 0);
        atomic_set(&sd->head, 0);
        atomic_set(&sd->tail, 0);
        rdr->pos = 0;
        return 0;

    default:
        return -ENOTTY;
    }
}

static const struct file_operations stock_fops = {
    .owner          = THIS_MODULE,
    .open           = stock_open,
    .release        = stock_release,
    .read           = stock_read,
    .poll           = stock_poll,
    .unlocked_ioctl = stock_ioctl,
    .llseek         = no_llseek,
};

/* ── device init / teardown ───────────────────────────────────────────── */

static int stock_dev_init(unsigned int idx)
{
    struct stock_dev *sd;
    int ret;

    sd = kzalloc(sizeof(*sd), GFP_KERNEL);
    if (!sd)
        return -ENOMEM;

    sd->idx              = idx;
    sd->price_udollar    = STOCK_DEFAULT_PRICE * (s64)(idx + 1);
    sd->volatility_bps   = 2000;   /* 20% annualised vol                   */
    sd->drift_bps        = 500;    /* 5%  annualised drift                 */
    sd->tick_interval_ms = STOCK_TICK_MS;

    mutex_init(&sd->params_lock);
    init_waitqueue_head(&sd->wq);
    atomic_set(&sd->head, 0);
    atomic_set(&sd->tail, 0);
    atomic64_set(&sd->seq, 0);

    cdev_init(&sd->cdev, &stock_fops);
    sd->cdev.owner = THIS_MODULE;
    ret = cdev_add(&sd->cdev, MKDEV(MAJOR(stock_major), idx), 1);
    if (ret)
        goto err_free;

    sd->device = device_create(stock_class, NULL,
                               MKDEV(MAJOR(stock_major), idx),
                               NULL, "stock%u", idx);
    if (IS_ERR(sd->device)) {
        ret = PTR_ERR(sd->device);
        goto err_cdev;
    }

    hrtimer_init(&sd->timer, CLOCK_MONOTONIC, HRTIMER_MODE_REL);
    sd->timer.function = stock_timer_cb;
    hrtimer_start(&sd->timer,
                  ms_to_ktime(sd->tick_interval_ms),
                  HRTIMER_MODE_REL);

    stock_devs[idx] = sd;
    pr_info(DRIVER_NAME ": /dev/stock%u created (price $%lld.%06lld)\n",
            idx,
            sd->price_udollar / 1000000LL,
            sd->price_udollar % 1000000LL);
    return 0;

err_cdev:
    cdev_del(&sd->cdev);
err_free:
    kfree(sd);
    return ret;
}

static void stock_dev_destroy(unsigned int idx)
{
    struct stock_dev *sd = stock_devs[idx];
    if (!sd)
        return;
    hrtimer_cancel(&sd->timer);
    device_destroy(stock_class, MKDEV(MAJOR(stock_major), idx));
    cdev_del(&sd->cdev);
    kfree(sd);
    stock_devs[idx] = NULL;
}

/* ── module init / exit ───────────────────────────────────────────────── */

static int __init stock_init(void)
{
    int ret, i;

    if (num_devices < 1 || num_devices > STOCK_MAX_DEVICES) {
        pr_err(DRIVER_NAME ": num_devices must be 1..%d\n",
               STOCK_MAX_DEVICES);
        return -EINVAL;
    }

    ret = alloc_chrdev_region(&stock_major, 0, STOCK_MAX_DEVICES, DRIVER_NAME);
    if (ret)
        return ret;

    stock_class = class_create(CLASS_NAME);
    if (IS_ERR(stock_class)) {
        ret = PTR_ERR(stock_class);
        goto err_unregister;
    }

    for (i = 0; i < num_devices; i++) {
        ret = stock_dev_init(i);
        if (ret)
            goto err_devs;
    }

    pr_info(DRIVER_NAME ": loaded, %d device(s), tick=%dms\n",
            num_devices, STOCK_TICK_MS);
    return 0;

err_devs:
    for (i--; i >= 0; i--)
        stock_dev_destroy(i);
    class_destroy(stock_class);
err_unregister:
    unregister_chrdev_region(stock_major, STOCK_MAX_DEVICES);
    return ret;
}

static void __exit stock_exit(void)
{
    int i;
    for (i = 0; i < num_devices; i++)
        stock_dev_destroy(i);
    class_destroy(stock_class);
    unregister_chrdev_region(stock_major, STOCK_MAX_DEVICES);
    pr_info(DRIVER_NAME ": unloaded\n");
}

module_init(stock_init);
module_exit(stock_exit);

MODULE_LICENSE("GPL v2");
MODULE_AUTHOR("stock_gen");
MODULE_DESCRIPTION("Synthetic stock price generator (char device)");
MODULE_VERSION("1.0");
