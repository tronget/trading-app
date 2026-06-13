import { useEffect, useState } from 'react';
import {
  FlatList,
  RefreshControl,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import { api, openTicker } from '../api';

export default function MarketScreen({ navigation }) {
  const [quotes, setQuotes] = useState([]);
  const [error, setError] = useState(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = async () => {
    setRefreshing(true);
    try {
      setQuotes(await api('/quotes'));
      setError(null);
    } catch (e) {
      setError(e.message);
    } finally {
      setRefreshing(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  useEffect(() => {
    if (quotes.length === 0) return;
    const close = openTicker(
      quotes.map((q) => q.symbol),
      (tick) =>
        setQuotes((current) =>
          current.map((q) =>
            q.symbol === tick.symbol ? { ...q, last: tick.price } : q,
          ),
        ),
    );
    return close;
  }, [quotes.length]);

  return (
    <View style={styles.container}>
      {error && <Text style={styles.error}>{error}</Text>}
      <FlatList
        data={quotes}
        keyExtractor={(item) => item.symbol}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={load} />}
        renderItem={({ item }) => (
          <TouchableOpacity
            style={styles.row}
            onPress={() => navigation.navigate('Instrument', { symbol: item.symbol })}
          >
            <View>
              <Text style={styles.symbol}>{item.symbol}</Text>
              <Text style={styles.name}>{item.name}</Text>
            </View>
            <View style={styles.right}>
              <Text style={styles.price}>
                {item.last != null ? `${item.last.toFixed(2)} $` : '—'}
              </Text>
              {item.changePct != null && (
                <Text style={{ color: item.changePct >= 0 ? '#1E8E3E' : '#D93025' }}>
                  {item.changePct >= 0 ? '+' : ''}
                  {item.changePct.toFixed(2)}%
                </Text>
              )}
            </View>
          </TouchableOpacity>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    padding: 16,
    borderBottomWidth: 1,
    borderColor: '#eee',
  },
  symbol: { fontSize: 17, fontWeight: 'bold' },
  name: { color: '#666' },
  right: { alignItems: 'flex-end' },
  price: { fontSize: 17 },
  error: { color: '#D93025', padding: 12, textAlign: 'center' },
});
