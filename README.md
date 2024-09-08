# GPS-less Navigation App

This is an Android application that provides real-time navigation without relying on GPS. It utilizes speed and angle data from sensors, such as a vehicle speed sensor and compass, to calculate and simulate paths. The application integrates with a Python Flask server backend for route calculations and real-time updates.

## Features

- **Offline Navigation**: Provides navigation without GPS, using speed and angle data to simulate the path.
- **Backend Integration**: Connects to a Python Flask server to calculate new coordinates and provide updated navigation routes.
- **Real-time Route Updates**: Continuously updates the route based on current speed, angle, and the camera feed.
- **Google Maps Integration**: Uses offline Google Maps for displaying routes and navigation paths.
- **Error Handling**: Gracefully handles backend communication failures.
- **Data Parsing**: Parses route data from the Python server to update the map in real time.

## Prerequisites

To run this application, you need:

- Android Studio 4.0+ installed.
- Python 3.8+ for the backend server.
- Flask library for the backend.
- Access to Google Maps SDK for Android.
- Android device with compass and speed sensors.

## Installation

### Android App

1. Clone this repository

2. Open the project in Android Studio.

3. Set up Google Maps SDK for Android following the official documentation:
   - [Google Maps SDK for Android](https://developers.google.com/maps/documentation/android-sdk/start)

4. Modify the `gradle.properties` file to add your Google Maps API key:

   ```gradle
   MAPS_API_KEY=your_google_maps_api_key
   ```

5. Connect your Android device via USB or use an emulator.

6. Run the app from Android Studio.

### Backend (Python Flask Server)

1. Navigate to the `backend` directory and install the required dependencies:

   ```bash
   pip install -r requirements.txt
   ```

2. Start the Flask server:

   ```bash
   python app.py
   ```

   The backend will listen for navigation requests from the Android app.

## Usage

1. Launch the Android app and select the starting and destination points on the map.
2. The app will communicate with the Flask server to calculate the route and display it.
3. As the vehicle moves, the app will provide real-time route updates based on speed and angle data.
4. The camera can be used to continuously assess the surroundings and improve navigation accuracy.

## Project Structure

```plaintext
/AndroidApp
  /app
    /src
      /main
        /java/com/yourapp/navigationapp
        /res
        /manifests
/backend
  app.py                # Flask server for route calculation
  requirements.txt       # Python dependencies
  ...
```

## Future Enhancements

- **AI Integration**: Implement AI to process camera feed and predict speed and location more accurately.
- **CRM Feature**: Potential extension to support customer management (CRM) functionalities.
- **Enhanced Navigation Algorithm**: Improve path prediction using additional data sources.

## Contributions

Feel free to submit a pull request or open an issue for feature suggestions or bug reports.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---
