# stock_gen — Linux Stock Price Generator Driver

Символьный драйвер ядра Linux. Генерирует синтетические котировки по модели GBM.

## Файлы

| Файл | Назначение |
|------|------------|
| `stock_gen.c` | Исходник драйвера (C, Linux kernel module) |
| `Makefile` | Сборка модуля |
| `StockTick.kt` | Kotlin data class + `StockDevReader` для JVM бэкенда |
| `README.docx` | Полная документация для разработчика |

## Быстрый старт

```bash
make
sudo insmod stock_gen.ko        # создаёт /dev/stock0 .. /dev/stock3
```

## Формат записи (32 байта, LE)

```
[0..7]   u64  seq             — порядковый номер тика
[8..15]  s64  timestamp_ns   — время генерации (ns, CLOCK_MONOTONIC)
[16..23] s64  price_udollar  — цена × 1_000_000 (микродоллары)
[24..27] u32  volume         — объём (акций/тик)
[28..31] u32  dev_idx        — индекс устройства
```

Подробности, примеры кода и описание ioctl-интерфейса — в `README.docx`.
