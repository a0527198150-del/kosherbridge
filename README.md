<div align="center">
<img width="1200" height="475" alt="GHBanner" src="https://ai.google.dev/static/site-assets/images/share-ais-513315318.png" />
</div>

# Run and deploy your AI Studio app

This contains everything you need to run your app locally.

View your app in AI Studio: https://ai.studio/apps/925e4192-4373-43f3-9337-c9d6f1c7c4d8

## 📱 גשר כשר (kosherbridge module)

The repository also contains a second app module **`kosherbridge`** — a Hebrew Bluetooth bridge app
that connects an Android player/tablet to a basic "kosher phone" over Bluetooth HFP (hands-free),
so calls ring on the player with answer / reject / dial and a built-in contacts app.
See [`kosherbridge/README.md`](kosherbridge/README.md) (Hebrew) for details.

## Run Locally

**Prerequisites:**  [Android Studio](https://developer.android.com/studio)


1. Open Android Studio
2. Select **Open** and choose the directory containing this project
3. Allow Android Studio to fix any incompatibilities as it imports the project.
4. Create a file named `.env` in the project directory and set `GEMINI_API_KEY` in that file to your Gemini API key (see `.env.example` for an example)
5. Remove this line from the app's `build.gradle.kts` file: `signingConfig = signingConfigs.getByName("debugConfig")`
6. Run the app on an emulator or physical device
