import { useCallback, useEffect, useState } from 'react';
import { FlatList, RefreshControl, StyleSheet, Text, View } from 'react-native';
import { api } from '../api';

export default function PortfolioScreen() {
  const [portfolio, setPortfolio] = useState(null);
  const [error, setError] = useState(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      setPortfolio(await api('/portfolio'));
      setError(null);
    } catch (e) {
      setError(e.message);
    } finally {
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, 5000);
    return () => clearInterval(timer);
  }, [load]);

  return (
    <View style={styles.container}>
      {error && <Text style={styles.error}>{error}</Text>}
      {portfolio && (
        <View style={styles.summary}>
          <Text style={styles.total}>
            {portfolio.totalValue} {portfolio.currency}
          </Text>
          <Text style={styles.cash}>Свободно: {portfolio.cash}</Text>
        </View>
      )}
      <FlatList
        data={portfolio?.positions ?? []}
        keyExtractor={(item) => item.symbol}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={load} />}
        ListEmptyComponent={<Text style={styles.empty}>Позиций пока нет</Text>}
        renderItem={({ item }) => (
          <View style={styles.row}>
            <View>
              <Text style={styles.symbol}>{item.symbol}</Text>
              <Text style={styles.muted}>
                {item.qty} шт × {item.avgPrice}
              </Text>
            </View>
            <View style={styles.right}>
              <Text>{item.last ?? '—'}</Text>
              {item.pnl != null && (
                <Text style={{ color: item.pnl.startsWith('-') ? '#D93025' : '#1E8E3E' }}>
                  {item.pnl.startsWith('-') ? item.pnl : `+${item.pnl}`}
                </Text>
              )}
            </View>
          </View>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  summary: { padding: 16, backgroundColor: '#0B5345' },
  total: { fontSize: 28, fontWeight: 'bold', color: 'white' },
  cash: { color: '#cde' },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 16,
    borderBottomWidth: 1,
    borderColor: '#eee',
  },
  symbol: { fontSize: 17, fontWeight: 'bold' },
  right: { alignItems: 'flex-end' },
  muted: { color: '#666' },
  empty: { textAlign: 'center', padding: 32, color: '#666' },
  error: { color: '#D93025', padding: 12, textAlign: 'center' },
});
