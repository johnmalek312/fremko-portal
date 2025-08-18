## 👁️ Overview
[![Twitter Follow](https://img.shields.io/twitter/follow/droid_run?style=social)](https://x.com/droid_run)

Fremko Portal is an Android accessibility service that provides real-time visual feedback and data collection for UI elements on the screen. It creates an interactive overlay that highlights clickable, checkable, editable, scrollable, and focusable elements, making it an invaluable tool for UI testing, automation development, and accessibility assessment.

## ✨ Features

### 🔍 Element Detection with Visual Overlay
- Identifies all interactive elements (clickable, checkable, editable, scrollable, and focusable)
- Handles nested elements and scrollable containers
- Assigns unique indices to interactive elements for reference

## ⭐ Support This Project

If you find Fremko Portal useful, please consider supporting the project by giving it a ⭐ on GitHub! Your star helps us:
- Gain visibility in the open source community
- Show appreciation for the development effort
- Encourage continued development and improvements


### ⚙️ Setup
1. Install the app on your Android device.
2. Enable the accessibility service in Android Settings → Accessibility → Fremko Portal.
3. Grant overlay permission when prompted.
4. Grant screen capture permssion when prompted.
5. Click on the wrench icon and type in your server WebSocket URL, usually it is `ws://your_local_ip:port` e.g. `ws://192.168.0.9:10001`. If you are using an emulator it is `ws://10.0.2.2:10001`.

### 🚀 Usage
1. Press connect.
2. Type out the goal and send.

### 💻 ADB Commands for Testing purposes.
```bash
# Get accessibility tree as JSON
adb shell content query --uri content://com.droidrun.portal/a11y_tree

# Get phone state as JSON
adb shell content query --uri content://com.droidrun.portal/phone_state

# Get combined state (accessibility tree + phone state) as JSON
adb shell content query --uri content://com.droidrun.portal/state
```

### 📤 Data Output
Element data is returned in JSON format through the ContentProvider queries. The response includes a status field and the requested data. All responses follow this structure:

```json
{
  "status": "success",
  "data": "..."
}
```

For error responses:
```json
{
  "status": "error", 
  "error": "Error message"
}
```

## 🔧 Technical Details
- Minimum Android API level: 30 (Android 11.0)
- Uses Android Accessibility Service API
- Implements custom drawing overlay using Window Manager
- Supports multi-window environments
- Built with Kotlin


## 🔄 Continuous Integration

This project uses GitHub Actions for automated building and releasing.

### 📦 Automated Builds

Every push to the main branch or pull request will trigger the build workflow that:
- Builds the Android app
- Creates the APK
- Uploads the APK as an artifact in the GitHub Actions run
