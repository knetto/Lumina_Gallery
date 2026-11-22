Google Cloud Service Account Setup for Lumina Gallery
=====================================================

You have two options to provide your Service Account credentials:

OPTION 1 (Bundled):
  Place your downloaded Google Cloud Service Account JSON key directly in this folder as:
    app/src/main/assets/credentials.json

OPTION 2 (Runtime - Recommended for security):
  Launch the app and tap "Select credentials.json" on the welcome screen (or in Settings).
  The app validates the key and stores it encrypted inside Android EncryptedSharedPreferences.

IMPORTANT - Share your Google Drive folders:
  1. Enable the "Google Drive API" in your Google Cloud project.
  2. Copy the "client_email" from your Service Account JSON
     (e.g. gallery-reader@my-project.iam.gserviceaccount.com).
  3. In Google Drive, right-click any root photo folder -> Share -> add that email as "Viewer".
  4. All nested subfolders inside the shared root folder become instantly accessible!
