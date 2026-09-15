# OPL SMB Server Node for Android

An Android application that hosts an SMBv1 server specifically optimized for streaming PS2 and PS1 games over the network to Open PS2 Loader (OPL).

## Features
- **Unprivileged SMB Hosting:** Runs on Port `10445` (No root required for modern OPL builds).
- **OPL Folder Generation:** Automatically creates `/PS2SMB/CD`, `/PS2SMB/DVD`, and `/PS2SMB/POPS` directories on startup.
- **Power & Wi-Fi Lock:** Prevents Android background process killing during gameplay.
- **Automated CI/CD:** GitHub Actions fetches native binaries dynamically and builds the APK on push.

## OPL Connection Settings
1. Open OPL on your PlayStation 2.
2. Go to **Network Settings** -> enable **Advanced Options**.
3. Set **SMB Server Port** to `10445`.
4. Set **Share Name** to `PS2SMB`.
5. Set **User** to `nobody` or leave blank (Guest Mode enabled).
