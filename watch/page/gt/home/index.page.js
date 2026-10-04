import * as hmUI from "@zos/ui";
import { px } from "@zos/utils";
import { getDeviceInfo } from "@zos/device";
import { setPageBrightTime, pauseDropWristScreenOff } from "@zos/display";
import {
  Vibrator,
  VIBRATOR_SCENE_NOTIFICATION,
  VIBRATOR_SCENE_DURATION,
  VIBRATOR_SCENE_SHORT_STRONG,
} from "@zos/sensor";
import { create, id } from "@zos/media";
import { LocalStorage } from "@zos/storage";
import BLEMaster, { ab2str } from "../../../lib/ble-master";

const { width: W, height: H } = getDeviceInfo();

// The board advertises as "JC-P4-C6" and exposes service 0xFFF0: write
// "freq <ms>" to 0xFFF1, replies arrive as notifications on 0xFFF2. The watch
// only connects reliably with full 128-bit UUIDs and pairing disabled.
const BOARD_NAME_PREFIX = "JC-";
const longUuid = (short) => "0000" + short.toLowerCase() + "-0000-1000-8000-00805f9b34fb";
const SERVICE_UUID = longUuid("FFF0");
const CMD_UUID = longUuid("FFF1");
const RESP_UUID = longUuid("FFF2");

const HALF_PERIOD_STEPS_MS = [20, 50, 100, 250, 500, 1000, 2000, 5000];
const SCAN_DURATION_MS = 20000;
const SCREEN_ON_MS = 300000;

const COLOR_BG = 0x101418;
const COLOR_TEXT = 0xffffff;
const COLOR_DIM = 0x9aa0a6;
const COLOR_OK = 0x34a853;
const COLOR_WARN = 0xfbbc04;
const COLOR_ERR = 0xea4335;
const COLOR_BTN = 0x1a73e8;
const COLOR_BTN_PRESS = 0x174ea6;

const ble = new BLEMaster();
const vibrator = new Vibrator();
const storage = new LocalStorage();
const ALERT_MODE_KEY = "alert_mode";
const ALERT_MODES = ["OFF", "VIB", "VIB+SND"];
const DISCONNECT_VIBRATION_MS = 5000;

let stepIndex = 4;
let ready = false;
let busy = false;
let alertMode = Number(storage.getItem(ALERT_MODE_KEY, 2));
if (!(alertMode >= 0 && alertMode < ALERT_MODES.length)) alertMode = 2;
let stopVibrationTimer = null;
let pulseTimer = null;
let player = null;
let statusText = null;
let valueText = null;
let alertButton = null;
let leaving = false;

function stopVibration() {
  clearInterval(pulseTimer);
  clearTimeout(stopVibrationTimer);
  vibrator.stop();
}

function vibrate(scene) {
  stopVibration();
  vibrator.setMode(scene);
  vibrator.start();
}

// Repeated plain pulses for exactly durationMs. The "call" vibration scene
// appeared to silence audio played at the same time.
function vibratePulses(durationMs) {
  stopVibration();
  const pulse = () => {
    vibrator.stop();
    vibrator.setMode(VIBRATOR_SCENE_DURATION);
    vibrator.start();
  };
  pulse();
  pulseTimer = setInterval(pulse, 700);
  stopVibrationTimer = setTimeout(stopVibration, durationMs);
}

// The watch only gives out one media player at a time (a second create() returns
// undefined), so a single instance is created once and reused for every sound.
function getPlayer() {
  if (player) return player;
  player = create(id.PLAYER);
  if (!player) return null;
  player.addEventListener(player.event.PREPARE, (result) => {
    if (result) player.start();
  });
  return player;
}

function playSound(file) {
  try {
    const p = getPlayer();
    if (!p) return;
    try {
      p.stop();
    } catch (e) {
      // nothing was playing
    }
    p.setSource(p.source.FILE, { file });
    p.prepare();
  } catch (e) {
    // no media support: the vibration alert still works
  }
}

function alertLinkUp() {
  if (alertMode >= 1) vibrate(VIBRATOR_SCENE_NOTIFICATION);
  if (alertMode === 2) playSound("link_up.mp3");
}

function alertLinkDown() {
  if (alertMode >= 1) vibratePulses(DISCONNECT_VIBRATION_MS);
  if (alertMode === 2) playSound("link_down.mp3");
}

function alertLabel() {
  return "Alert: " + ALERT_MODES[alertMode];
}

function cycleAlertMode() {
  alertMode = (alertMode + 1) % ALERT_MODES.length;
  storage.setItem(ALERT_MODE_KEY, alertMode);
  alertButton.setProperty(hmUI.prop.TEXT, alertLabel());
  if (alertMode === 1) vibrate(VIBRATOR_SCENE_SHORT_STRONG);
  if (alertMode === 2) playSound("link_up.mp3");
}

function setStatus(text, color) {
  statusText.setProperty(hmUI.prop.MORE, { text, color });
}

function showValue() {
  valueText.setProperty(hmUI.prop.TEXT, HALF_PERIOD_STEPS_MS[stepIndex] + " ms");
}

function seenSummary() {
  const devices = ble.get.devices();
  const names = [];
  for (const mac in devices) names.push(devices[mac].dev_name || mac.slice(-5));
  return names.length + " seen: " + names.slice(0, 3).join(", ");
}

function findBoardMac() {
  const devices = ble.get.devices();
  for (const mac in devices) {
    const dev = devices[mac];
    if (dev.dev_name && dev.dev_name.indexOf(BOARD_NAME_PREFIX) === 0) return mac;
  }
  return null;
}

function fail(message) {
  ready = false;
  busy = false;
  ble.quit();
  setStatus(message + ". Tap to retry", COLOR_ERR);
}

function scanAndConnect() {
  if (busy) return;
  busy = true;
  ready = false;
  setStatus("Searching board...", COLOR_WARN);

  const started = ble.startScan(
    () => {
      const mac = findBoardMac();
      if (!mac) return;
      ble.stopScan();
      connect(mac);
    },
    {
      duration: SCAN_DURATION_MS,
      on_duration: () => {
        if (!ready && !findBoardMac()) fail("No board. " + seenSummary());
      },
    }
  );
  if (!started) fail("Scan failed");
}

function connect(mac) {
  setStatus("Connecting...", COLOR_WARN);
  ble.connect(mac, (result) => {
    if (result.connected) {
      buildProfile();
      return;
    }
    if (leaving) return;
    const wasConnected = ready;
    fail("Link lost [" + result.status + "]");
    if (wasConnected) alertLinkDown();
  });
}

function buildProfile() {
  const services = {
    [SERVICE_UUID]: {
      [CMD_UUID]: [],
      [RESP_UUID]: ["2902"],
    },
  };
  const profile = ble.generateProfileObject(services);
  profile.pair = false;
  ble.startListener(profile, (response) => {
    if (!response.success) {
      fail("Profile " + response.code);
      return;
    }
    ble.on.charaNotification((uuid, data) => {
      if (String(uuid).toLowerCase() === RESP_UUID) setStatus(ab2str(data), COLOR_OK);
    });
    ble.write.enableCharaNotifications(RESP_UUID, true);
    ready = true;
    busy = false;
    setStatus("Connected", COLOR_OK);
    alertLinkUp();
  });
}

function sendCurrentValue() {
  if (!ready) {
    scanAndConnect();
    return;
  }
  const cmd = "freq " + HALF_PERIOD_STEPS_MS[stepIndex];
  ble.write.characteristic(CMD_UUID, cmd);
  setStatus("Sent: " + cmd, COLOR_DIM);
}

function changeStep(delta) {
  const next = stepIndex + delta;
  if (next < 0 || next >= HALF_PERIOD_STEPS_MS.length) return;
  stepIndex = next;
  showValue();
  sendCurrentValue();
}

function createButton(x, label, onClick) {
  hmUI.createWidget(hmUI.widget.BUTTON, {
    x: px(x),
    y: px(300),
    w: px(170),
    h: px(80),
    radius: px(20),
    text: label,
    text_size: px(28),
    color: COLOR_TEXT,
    normal_color: COLOR_BTN,
    press_color: COLOR_BTN_PRESS,
    click_func: onClick,
  });
}

Page({
  onInit() {
    setPageBrightTime({ brightTime: SCREEN_ON_MS });
    pauseDropWristScreenOff({ duration: SCREEN_ON_MS });
  },
  build() {
    hmUI.createWidget(hmUI.widget.FILL_RECT, { x: 0, y: 0, w: W, h: H, color: COLOR_BG });

    hmUI.createWidget(hmUI.widget.TEXT, {
      x: px(40),
      y: px(50),
      w: W - px(80),
      h: px(50),
      text: "LED speed",
      text_size: px(34),
      color: COLOR_TEXT,
      align_h: hmUI.align.CENTER_H,
      align_v: hmUI.align.CENTER_V,
    });

    statusText = hmUI.createWidget(hmUI.widget.TEXT, {
      x: px(40),
      y: px(100),
      w: W - px(80),
      h: px(64),
      text: "Starting...",
      text_size: px(22),
      color: COLOR_DIM,
      align_h: hmUI.align.CENTER_H,
      align_v: hmUI.align.CENTER_V,
      text_style: hmUI.text_style.WRAP,
    });
    statusText.addEventListener(hmUI.event.CLICK_UP, () => {
      if (!ready) scanAndConnect();
    });

    valueText = hmUI.createWidget(hmUI.widget.TEXT, {
      x: px(40),
      y: px(165),
      w: W - px(80),
      h: px(110),
      text: "",
      text_size: px(72),
      color: COLOR_TEXT,
      align_h: hmUI.align.CENTER_H,
      align_v: hmUI.align.CENTER_V,
    });
    showValue();

    createButton(60, "SLOWER", () => changeStep(1));
    createButton(250, "FASTER", () => changeStep(-1));

    alertButton = hmUI.createWidget(hmUI.widget.BUTTON, {
      x: px(140),
      y: px(395),
      w: px(200),
      h: px(50),
      radius: px(16),
      text: alertLabel(),
      text_size: px(22),
      color: COLOR_TEXT,
      normal_color: COLOR_BTN,
      press_color: COLOR_BTN_PRESS,
      click_func: cycleAlertMode,
    });

    scanAndConnect();
  },
  onDestroy() {
    leaving = true;
    stopVibration();
    try {
      if (player) player.stop();
    } catch (e) {
      // nothing was playing
    }
    ble.quit();
  },
});
