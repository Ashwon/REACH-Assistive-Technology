// REACH haptic band: non-blocking pulse scheduler for the ESP32-S3 two-motor controller.
//
// STATUS: a patch FOR REVIEW. It has not been compiled against your board setup or flashed. The working firmware
// tested with Serial Bluetooth Terminal was not available, so this file replaces only what the Android app relies on
// and keeps the same contract:
//   device name "REACH_Haptic_Device", Nordic UART Service UUIDs, RX = write (with response), TX = notify,
//   command "MOTOR,POWER,DURATION_MS" (MOTOR L/R/B, POWER 0..255, DURATION_MS 0..MAX_PULSE_MS).
// Additions that do not change the protocol:
//   - POWER 0 is STOP: clears the queue and turns both motors off at once ("B,0,0" is what the app sends).
//   - Pulses run from loop() with millis(); nothing blocks, so a STOP lands within one loop() pass.
//   - Malformed payloads are rejected; replies on TX: "OK <cmd>", "ERR <reason>", and "OFF" whenever both motors stop.
//   - Disconnect and reset turn both motors off; queued pulses that waited too long are dropped, never played late.
//
// Before flashing: copy the motor pins (and the drive code, if your driver is not a plain PWM input) from your
// working sketch. They are deliberately not guessed here.
// Tested with: nothing yet. Built for the arduino-esp32 core's bundled BLE library (2.x or 3.x).

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// #define LEFT_MOTOR_PIN  <copy from your working sketch>
// #define RIGHT_MOTOR_PIN <copy from your working sketch>
#if !defined(LEFT_MOTOR_PIN) || !defined(RIGHT_MOTOR_PIN)
#error "Set LEFT_MOTOR_PIN and RIGHT_MOTOR_PIN to the GPIOs your working firmware uses."
#endif

#define DEVICE_NAME  "REACH_Haptic_Device"
#define SERVICE_UUID "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define RX_UUID      "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
#define TX_UUID      "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

static const uint16_t MAX_PULSE_MS = 1000; // longest single pulse accepted (the app's longest is 400 ms)
static const uint32_t STALE_MS = 500;      // a queued pulse not started within this is dropped
static const uint8_t QUEUE_LEN = 8;
static const size_t MAX_CMD_LEN = 24;

struct Pulse { char motor; uint8_t power; uint16_t ms; uint32_t queuedAt; };

// Shared between the BLE task (onWrite / onDisconnect) and loop(): guarded by mux.
static portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;
static Pulse queue_[QUEUE_LEN];
static uint8_t head = 0, count = 0;
static volatile bool stopRequested = false;
static volatile bool advertiseAgain = false;

// loop() only.
static bool active = false;
static uint32_t activeSince = 0, activeMs = 0;
static BLECharacteristic *tx = nullptr;
static volatile bool connected = false;

static void reply(const char *text) {
  if (!tx || !connected) return;
  tx->setValue((uint8_t *)text, strlen(text));
  tx->notify();
}

// Adapt here if your motor driver needs something other than a PWM duty on one pin per motor.
static void drive(int pin, uint8_t power) { analogWrite(pin, power); }

static void motorsOff() {
  drive(LEFT_MOTOR_PIN, 0);
  drive(RIGHT_MOTOR_PIN, 0);
}

static void startPulse(const Pulse &p) {
  drive(LEFT_MOTOR_PIN, (p.motor == 'L' || p.motor == 'B') ? p.power : 0);
  drive(RIGHT_MOTOR_PIN, (p.motor == 'R' || p.motor == 'B') ? p.power : 0);
  active = true; activeSince = millis(); activeMs = p.ms;
}

// Parses "M,P,D" strictly: one of L/R/B, then two integers in range, nothing else. Returns an error or nullptr.
static const char *parse(const char *s, Pulse &out) {
  char m; int power, ms; char extra;
  if (sscanf(s, "%c,%d,%d%c", &m, &power, &ms, &extra) != 3) return "ERR format: use MOTOR,POWER,DURATION_MS";
  if (m != 'L' && m != 'R' && m != 'B') return "ERR motor: use L, R or B";
  if (power < 0 || power > 255) return "ERR power: 0..255";
  if (ms < 0 || ms > MAX_PULSE_MS) return "ERR duration: 0..1000 ms";
  if (power > 0 && ms == 0) return "ERR duration: must be > 0";
  out.motor = m; out.power = (uint8_t)power; out.ms = (uint16_t)ms;
  return nullptr;
}

class RxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *c) override {
    String raw = String(c->getValue().c_str()); // std::string (core 2.x) and String (core 3.x) both have c_str()
    raw.trim();                                  // terminal apps append \r\n
    if (raw.length() == 0 || raw.length() > MAX_CMD_LEN) { reply("ERR length"); return; }
    Pulse p;
    const char *err = parse(raw.c_str(), p);
    if (err) { reply(err); return; }
    if (p.power == 0) { // STOP: nothing queued may run after it
      portENTER_CRITICAL(&mux); count = 0; stopRequested = true; portEXIT_CRITICAL(&mux);
      reply("OK STOP");
      return;
    }
    p.queuedAt = millis();
    bool full;
    portENTER_CRITICAL(&mux);
    full = count >= QUEUE_LEN;
    if (!full) { queue_[(head + count) % QUEUE_LEN] = p; count++; }
    portEXIT_CRITICAL(&mux);
    String ok = "OK " + raw;
    reply(full ? "ERR busy" : ok.c_str());
  }
};

class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *) override { connected = true; }
  void onDisconnect(BLEServer *) override {
    connected = false;
    portENTER_CRITICAL(&mux); count = 0; stopRequested = true; portEXIT_CRITICAL(&mux); // motors off in loop()
    advertiseAgain = true;
  }
};

// Next pulse that is still fresh, or false. Stale ones are dropped.
static bool nextPulse(Pulse &out) {
  uint32_t now = millis();
  bool got = false;
  portENTER_CRITICAL(&mux);
  while (count > 0 && !got) {
    out = queue_[head]; head = (head + 1) % QUEUE_LEN; count--;
    got = (now - out.queuedAt) <= STALE_MS;
  }
  portEXIT_CRITICAL(&mux);
  return got;
}

void setup() {
  pinMode(LEFT_MOTOR_PIN, OUTPUT);
  pinMode(RIGHT_MOTOR_PIN, OUTPUT);
  motorsOff(); // a reset never leaves a motor running
  Serial.begin(115200);

  BLEDevice::init(DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService *svc = server->createService(SERVICE_UUID);
  tx = svc->createCharacteristic(TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  tx->addDescriptor(new BLE2902());
  BLECharacteristic *rx = svc->createCharacteristic(RX_UUID, BLECharacteristic::PROPERTY_WRITE);
  rx->setCallbacks(new RxCallbacks());
  svc->start();
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  BLEDevice::startAdvertising();
  Serial.println("REACH band ready");
}

void loop() {
  if (stopRequested) {
    portENTER_CRITICAL(&mux); stopRequested = false; count = 0; portEXIT_CRITICAL(&mux);
    motorsOff(); active = false;
    reply("OFF");
  }
  if (active && millis() - activeSince >= activeMs) {
    motorsOff(); active = false;
    reply("OFF");
  }
  if (!active) {
    Pulse p;
    if (nextPulse(p)) startPulse(p);
  }
  if (advertiseAgain) { advertiseAgain = false; BLEDevice::startAdvertising(); }
  delay(1); // yield to the idle task; never more than a millisecond
}
