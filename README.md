# thermal_camera
ESP32 Thermal Camera with Remote Android App

This project expands upon the M5StickC Thermal Camera demo to include an Android app that can remotely stream the tempurature data. The ESP32 code is enhanced to include an HTTP server that streams the temperature data in UDP packets. The android app connects to the ESP32 router and receives the UDP packets of temp data and presents them on the screen with adjustable high and low temperature limits.

MLX90640 example that was modified:  https://github.com/m5stack/M5StickC/tree/master/examples/Hat/MLX90640

![android_thermal](https://user-images.githubusercontent.com/71778976/159581323-9d60d776-90a5-4e7a-9e0c-41c9d9202d75.png)
