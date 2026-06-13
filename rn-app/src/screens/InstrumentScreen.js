import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Button,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import Svg, { Polyline } from 'react-native-svg';
import { api, openTicker } from '../api';

const INTERVALS = ['1m', '5m', '15m', '1h'];

function Chart({ candles }) {
  const width = 340;
  const height = 200;
  if (candles.length < 2) return <Text style={styles.muted}>Нет данных за период</Text>;
  const values = candles.map((c) => c.close);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const points = values
    .map(
      (v, i) =>
        `${(i / (values.length - 1)) * width},${height - ((v - min) / range) * height * 0.9 - height * 0.05}`,
    )
    .join(' ');
  return (
    <Svg width={width} height={height}>
      <Polyline points={points} fill="none" stroke="#0B5345" strokeWidth="2" />
    </Svg>
  );
}

export default function InstrumentScreen({ route }) {
  const { symbol } = route.params;
  const [candles, setCandles] = useState([]);
  const [interval, setIntervalValue] = useState('1m');
  const [last, setLast] = useState(null);
  const [loading, setLoading] = useState(true);
  const [qty, setQty] = useState('1');
  const [type, setType] = useState('MARKET');
  const [price, setPrice] = useState('');
  const [result, setResult] = useState(null);

  const loadCandles = async (iv) => {
    setLoading(true);
    try {
      setCandles(await api(`/quotes/${symbol}/history?interval=${iv}&limit=120`));
    } catch (e) {
      setResult(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadCandles(interval);
  }, [interval]);

  useEffect(() => openTicker([symbol], (tick) => setLast(tick.price)), [symbol]);

  const placeOrder = async (side) => {
    setResult(null);
    try {
      const order = await api('/orders', {
        method: 'POST',
        body: JSON.stringify({
          symbol,
          side,
          type,
          qty,
          price: type === 'LIMIT' ? price : undefined,
        }),
      });
      setResult(`Ордер #${order.id}: ${order.status}`);
    } catch (e) {
      setResult(`Ошибка: ${e.message}`);
    }
  };

  return (
    <ScrollView contentContainerStyle={styles.container}>
      <View style={styles.header}>
        <Text style={styles.symbol}>{symbol}</Text>
        <Text style={styles.price}>{last != null ? `${last.toFixed(2)} $` : '—'}</Text>
      </View>

      {loading ? <ActivityIndicator /> : <Chart candles={candles} />}

      <View style={styles.chips}>
        {INTERVALS.map((iv) => (
          <TouchableOpacity
            key={iv}
            style={[styles.chip, interval === iv && styles.chipActive]}
            onPress={() => setIntervalValue(iv)}
          >
            <Text style={interval === iv ? styles.chipTextActive : null}>{iv}</Text>
          </TouchableOpacity>
        ))}
      </View>

      <Text style={styles.section}>Новый ордер</Text>
      <View style={styles.chips}>
        {['MARKET', 'LIMIT'].map((t) => (
          <TouchableOpacity
            key={t}
            style={[styles.chip, type === t && styles.chipActive]}
            onPress={() => setType(t)}
          >
            <Text style={type === t ? styles.chipTextActive : null}>{t}</Text>
          </TouchableOpacity>
        ))}
      </View>
      <TextInput
        style={styles.input}
        value={qty}
        onChangeText={setQty}
        placeholder="Количество"
        keyboardType="numeric"
      />
      {type === 'LIMIT' && (
        <TextInput
          style={styles.input}
          value={price}
          onChangeText={setPrice}
          placeholder="Цена"
          keyboardType="numeric"
        />
      )}
      <View style={styles.buttons}>
        <Button title="Купить" color="#1E8E3E" onPress={() => placeOrder('BUY')} />
        <Button title="Продать" color="#D93025" onPress={() => placeOrder('SELL')} />
      </View>
      {result && <Text style={styles.result}>{result}</Text>}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { padding: 16, gap: 12, alignItems: 'stretch' },
  header: { flexDirection: 'row', justifyContent: 'space-between' },
  symbol: { fontSize: 28, fontWeight: 'bold' },
  price: { fontSize: 24 },
  chips: { flexDirection: 'row', gap: 8 },
  chip: {
    paddingHorizontal: 14,
    paddingVertical: 6,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: '#0B5345',
  },
  chipActive: { backgroundColor: '#0B5345' },
  chipTextActive: { color: 'white' },
  section: { fontSize: 18, fontWeight: 'bold', marginTop: 8 },
  input: { borderWidth: 1, borderColor: '#ccc', borderRadius: 8, padding: 12 },
  buttons: { flexDirection: 'row', justifyContent: 'space-around', gap: 16 },
  result: { textAlign: 'center', marginTop: 8 },
  muted: { color: '#666', textAlign: 'center', padding: 40 },
});
