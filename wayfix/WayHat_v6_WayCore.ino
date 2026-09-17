#include <Arduino.h>
#include <DHT.h>
#include "BluetoothSerial.h"

// WAYHAT -> WAYCORE
// ESP32-WROOM-32 / Bluetooth Classic SPP
// Sensors: 3x HC-SR04, TF-Luna, DHT11, buzzer

#define DR_TRIG 25
#define DR_ECHO 13
#define IZ_TRIG 26
#define IZ_ECHO 14
#define AT_TRIG 27
#define AT_ECHO 32
#define TF_RX 16
#define TF_TX 17
#define DHT_PIN 33
#define BUZZER 4

#define DHT_TYPE DHT11
#define BT_NAME "WayHat-Karbys"

BluetoothSerial BT;
HardwareSerial TF(2);
DHT dht(DHT_PIN, DHT_TYPE);

struct State {
  int right = -1, left = -1, rear = -1, tf = -1, closest = -1;
  float temp = NAN, hum = NAN;
  bool dhtOk = false;
  int threshold = 50;
  bool buzzer = true;
  bool safe = true;
} st;

bool lastBtClient = false;

String rx;
uint32_t nextSensors = 0, nextTelemetry = 0, nextBeep = 0;
bool beepOn = false;

long hc(int trig, int echo) {
  digitalWrite(trig, LOW); delayMicroseconds(2);
  digitalWrite(trig, HIGH); delayMicroseconds(10);
  digitalWrite(trig, LOW);
  uint32_t t = pulseIn(echo, HIGH, 25000);
  return t ? (long)(t * 0.0343f * 0.5f) : -1;
}

void readTF() {
  static uint8_t b[9], n = 0;
  while (TF.available()) {
    uint8_t x = TF.read();
    if (n == 0 && x != 0x59) continue;
    if (n == 1 && x != 0x59) { n = 0; continue; }
    b[n++] = x;
    if (n == 9) {
      uint8_t sum = 0; for (int i=0;i<8;i++) sum += b[i];
      if (sum == b[8]) st.tf = b[2] | (b[3] << 8);
      n = 0;
    }
  }
}

int minValid(int a, int b, int c, int d) {
  int m = 9999;
  if (a > 0 && a < m) m = a;
  if (b > 0 && b < m) m = b;
  if (c > 0 && c < m) m = c;
  if (d > 0 && d < m) m = d;
  return m == 9999 ? -1 : m;
}

void readSensors() {
  st.right = hc(DR_TRIG, DR_ECHO);
  st.left  = hc(IZ_TRIG, IZ_ECHO);
  st.rear  = hc(AT_TRIG, AT_ECHO);
  readTF();
  float t = dht.readTemperature(), h = dht.readHumidity();
  st.dhtOk = !isnan(t) && !isnan(h);
  if (!isnan(t)) st.temp = t;
  if (!isnan(h)) st.hum = h;
  st.closest = minValid(st.right, st.left, st.rear, st.tf);
}

void updateBuzzer() {
  if (!st.safe || !st.buzzer || st.closest <= 0 || st.closest > st.threshold) {
    noTone(BUZZER); beepOn = false; return;
  }
  int d = constrain(st.closest, 5, st.threshold);
  uint32_t gap = map(d, 5, st.threshold, 35, 650);
  uint32_t now = millis();
  if (now >= nextBeep) {
    if (!beepOn) { tone(BUZZER, 2100); beepOn = true; nextBeep = now + 45; }
    else { noTone(BUZZER); beepOn = false; nextBeep = now + gap; }
  }
}

void telemetry() {
  if (!BT.hasClient()) return;
  String temp = isnan(st.temp) ? "null" : String(st.temp, 1);
  String hum  = isnan(st.hum)  ? "null" : String(st.hum, 1);
  BT.printf("{\"type\":\"telemetry\",\"available\":true,\"uptime_ms\":%lu,\"right\":%d,\"left\":%d,\"rear\":%d,\"tf\":%d,\"closest\":%d,\"temp\":%s,\"hum\":%s,\"dht_ok\":%s,\"threshold\":%d,\"mode\":\"%s\",\"buzzer\":%s,\"right_ok\":%s,\"left_ok\":%s,\"rear_ok\":%s,\"tf_ok\":%s}\n",
    millis(), st.right, st.left, st.rear, st.tf, st.closest, temp.c_str(), hum.c_str(),
    st.dhtOk ? "true" : "false", st.threshold, st.safe ? "SAFE" : "CHAT",
    st.buzzer ? "true" : "false",
    st.right > 0 ? "true" : "false", st.left > 0 ? "true" : "false",
    st.rear > 0 ? "true" : "false", st.tf > 0 ? "true" : "false");
}

String valueOf(const String &s, const char *key) {
  String k = String("\"") + key + "\"";
  int p = s.indexOf(k); if (p < 0) return "";
  p = s.indexOf(':', p); if (p < 0) return ""; p++;
  while (p < (int)s.length() && (s[p] == ' ' || s[p] == '"')) p++;
  int e = p;
  while (e < (int)s.length() && s[e] != ',' && s[e] != '}' && s[e] != '"') e++;
  return s.substring(p, e);
}

void command(const String &s) {
  String type = valueOf(s, "type");
  String id = valueOf(s, "id");
  bool ok = true;
  String message = "OK";

  if (type == "config") {
    String v = valueOf(s, "threshold");
    if (v.length()) st.threshold = constrain(v.toInt(), 20, 150);
    v = valueOf(s, "mode");
    if (v.length()) {
      if (v.equalsIgnoreCase("SAFE") || v.equalsIgnoreCase("CHAT")) st.safe = v.equalsIgnoreCase("SAFE");
      else { ok = false; message = "Modo invalido"; }
    }
    v = valueOf(s, "buzzer");
    if (v.length()) st.buzzer = v.equalsIgnoreCase("true") || v == "1";
  } else if (type == "command") {
    String n = valueOf(s, "name");
    if (n == "BUZZER_TEST") tone(BUZZER, 2200, 250);
    else if (n == "SENSORS") readSensors();
    else if (n == "SAFE") st.safe = true;
    else if (n == "CHAT") st.safe = false;
    else { ok = false; message = "Comando no permitido"; }
  } else {
    ok = false; message = "Tipo no permitido";
  }

  if (BT.hasClient()) {
    BT.printf("{\"type\":\"ack\",\"id\":\"%s\",\"ok\":%s,\"message\":\"%s\"}\n",
      id.c_str(), ok ? "true" : "false", message.c_str());
  }
}

void readBluetooth() {
  while (BT.available()) {
    char c = (char)BT.read();
    if (c == '\n') { rx.trim(); if (rx.length()) command(rx); rx = ""; }
    else if (rx.length() < 350) rx += c;
    else rx = "";
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(DR_TRIG, OUTPUT); pinMode(DR_ECHO, INPUT);
  pinMode(IZ_TRIG, OUTPUT); pinMode(IZ_ECHO, INPUT);
  pinMode(AT_TRIG, OUTPUT); pinMode(AT_ECHO, INPUT);
  pinMode(BUZZER, OUTPUT);
  dht.begin();
  TF.begin(115200, SERIAL_8N1, TF_RX, TF_TX);
  if (!BT.begin(BT_NAME)) {
    Serial.println("ERROR: Bluetooth SPP no pudo iniciar");
  } else {
    Serial.print("Bluetooth SPP listo: ");
    Serial.println(BT_NAME);
  }
  readSensors();
  Serial.println("WAYHAT -> WAYCORE LISTO");
}

void loop() {
  uint32_t now = millis();
  readBluetooth();
  readTF();
  bool btClient = BT.hasClient();
  if (btClient != lastBtClient) {
    lastBtClient = btClient;
    Serial.println(btClient ? "WAYHAT: CELULAR CONECTADO" : "WAYHAT: CELULAR DESCONECTADO");
  }
  if ((int32_t)(now - nextSensors) >= 0) { nextSensors = now + 250; readSensors(); }
  updateBuzzer();
  if ((int32_t)(now - nextTelemetry) >= 0) { nextTelemetry = now + 500; telemetry(); }
  delay(2);
}
