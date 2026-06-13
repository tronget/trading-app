import { useCallback, useEffect, useState } from 'react';
import {
  Button,
  FlatList,
  RefreshControl,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { api } from '../api';

export default function HistoryScreen() {
  const [tab, setTab] = useState('orders');
  const [orders, setOrders] = useState([]);
  const [trades, setTrades] = useState([]);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState(null);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      setOrders(await api('/orders'));
      setTrades(await api('/trades'));
      setError(null);
    } catch (e) {
      setError(e.message);
    } finally {
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  const cancelOrder = async (id) => {
    try {
      await api(`/orders/${id}`, { method: 'DELETE' });
      load();
    } catch (e) {
      setError(e.message);
    }
  };

  const data = tab === 'orders' ? orders : trades;

  return (
    <View style={styles.container}>
      <View style={styles.tabs}>
        {['orders', 'trades'].map((t) => (
          <TouchableOpacity
            key={t}
            style={[styles.tab, tab === t && styles.tabActive]}
            onPress={() => setTab(t)}
          >
            <Text style={tab === t ? styles.tabTextActive : null}>
              {t === 'orders' ? 'Ордера' : 'Сделки'}
            </Text>
          </TouchableOpacity>
        ))}
      </View>
      {error && <Text style={styles.error}>{error}</Text>}
      <FlatList
        data={data}
        keyExtractor={(item) => String(item.id)}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={load} />}
        renderItem={({ item }) => (
          <View style={styles.row}>
            <View>
              <Text style={{ color: item.side === 'BUY' ? '#1E8E3E' : '#D93025', fontWeight: 'bold' }}>
                {item.side} {item.qty} {item.symbol}
              </Text>
              <Text style={styles.muted}>
                {tab === 'orders'
                  ? `${item.type}${item.price ? ` @ ${item.price}` : ''} · ${item.status}`
                  : `@ ${item.price} · ${item.executedAt?.slice(0, 19)}`}
              </Text>
            </View>
            {tab === 'orders' && item.status === 'NEW' && (
              <Button title="Отменить" onPress={() => cancelOrder(item.id)} />
            )}
          </View>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  tabs: { flexDirection: 'row' },
  tab: { flex: 1, padding: 14, alignItems: 'center', borderBottomWidth: 2, borderColor: '#eee' },
  tabActive: { borderColor: '#0B5345' },
  tabTextActive: { color: '#0B5345', fontWeight: 'bold' },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: 1,
    borderColor: '#eee',
  },
  muted: { color: '#666' },
  error: { color: '#D93025', padding: 12, textAlign: 'center' },
});
