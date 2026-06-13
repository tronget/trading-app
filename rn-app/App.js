import { NavigationContainer } from '@react-navigation/native';
import { createBottomTabNavigator } from '@react-navigation/bottom-tabs';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { useEffect, useState } from 'react';
import { Button } from 'react-native';
import { clearTokens, getAccessToken } from './src/api';
import LoginScreen from './src/screens/LoginScreen';
import MarketScreen from './src/screens/MarketScreen';
import InstrumentScreen from './src/screens/InstrumentScreen';
import PortfolioScreen from './src/screens/PortfolioScreen';
import HistoryScreen from './src/screens/HistoryScreen';

const Tab = createBottomTabNavigator();
const Stack = createNativeStackNavigator();

function Tabs() {
  return (
    <Tab.Navigator screenOptions={{ headerShown: false }}>
      <Tab.Screen name="Рынок" component={MarketScreen} />
      <Tab.Screen name="Портфель" component={PortfolioScreen} />
      <Tab.Screen name="История" component={HistoryScreen} />
    </Tab.Navigator>
  );
}

export default function App() {
  const [loggedIn, setLoggedIn] = useState(null);

  useEffect(() => {
    getAccessToken().then((token) => setLoggedIn(token != null));
  }, []);

  if (loggedIn === null) return null;
  if (!loggedIn) return <LoginScreen onLoggedIn={() => setLoggedIn(true)} />;

  return (
    <NavigationContainer>
      <Stack.Navigator>
        <Stack.Screen
          name="Trading"
          component={Tabs}
          options={{
            headerRight: () => (
              <Button
                title="Выйти"
                onPress={async () => {
                  await clearTokens();
                  setLoggedIn(false);
                }}
              />
            ),
          }}
        />
        <Stack.Screen
          name="Instrument"
          component={InstrumentScreen}
          options={({ route }) => ({ title: route.params.symbol })}
        />
      </Stack.Navigator>
    </NavigationContainer>
  );
}
