import { useState } from 'react';
import {
  ActivityIndicator,
  Button,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { api, saveTokens } from '../api';

export default function LoginScreen({ onLoggedIn }) {
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  const submit = async (path) => {
    setLoading(true);
    setError(null);
    try {
      const body = await api(path, {
        method: 'POST',
        body: JSON.stringify({ login: login.trim(), password }),
      });
      await saveTokens(body.accessToken, body.refreshToken);
      onLoggedIn();
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  return (
    <View style={styles.container}>
      <Text style={styles.title}>Trading</Text>
      <TextInput
        style={styles.input}
        placeholder="Логин"
        autoCapitalize="none"
        value={login}
        onChangeText={setLogin}
      />
      <TextInput
        style={styles.input}
        placeholder="Пароль"
        secureTextEntry
        value={password}
        onChangeText={setPassword}
      />
      {loading ? (
        <ActivityIndicator />
      ) : (
        <View style={styles.buttons}>
          <Button title="Войти" onPress={() => submit('/auth/login')} />
          <Button title="Регистрация" onPress={() => submit('/auth/register')} />
        </View>
      )}
      {error && <Text style={styles.error}>{error}</Text>}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, justifyContent: 'center', padding: 32, gap: 12 },
  title: { fontSize: 32, fontWeight: 'bold', textAlign: 'center', marginBottom: 16 },
  input: { borderWidth: 1, borderColor: '#ccc', borderRadius: 8, padding: 12 },
  buttons: { gap: 8 },
  error: { color: '#D93025', textAlign: 'center' },
});
