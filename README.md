# thermal_camera
ESP32 Thermal Camera with Remote Android App
![android_thermal](https://user-images.githubusercontent.com/71778976/159580628-c10a3ee9-ba42-4cf2-a780-332e39e4d13b.png)

This project expands upon the M5StickC Thermal Camera demo to include an Android app that can remotely stream the tempurature data. The ESP32 code is enhanced to include an HTTP server that streams the temperature data in UDP packets. The android app connects to the ESP32 router and receives the UDP packets of temp data and presents them on the screen with adjustable high and low temperature limits.
