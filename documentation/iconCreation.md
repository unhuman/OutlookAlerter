# Icon Creation and Management for OutlookAlerter

This document outlines the process for creating and managing icons to be included in the OutlookAlerter build.

## 1. Creating the Icon

1. **Design the Icon**:
   - Use a design tool like Adobe Illustrator, Figma, or any other graphic design software to create the icon.
   - Ensure the icon is square and has a transparent background.

2. **Export Icon Variants**:
   - Export the icon in multiple sizes (e.g., 16x16, 32x32, 128x128, 256x256, 512x512) as PNG files.
   - Save these files in a folder named `icon.iconset`.

3. **Generate `.icns` File**:
   - Use the `iconutil` command-line tool on macOS to convert the `icon.iconset` folder into an `.icns` file:
     ```bash
     iconutil -c icns icon.iconset
     ```
   - This will create a file named `icon.icns` in the same directory.

## 2. Managing the Icon in the Build

1. **Place the `.icns` File**:
   - Copy the `icon.icns` file to the `src/main/resources` directory of the project.

2. **Update `Info.plist`**:
   - Ensure the `CFBundleIconFile` key in the `Info.plist` file matches the name of the `.icns` file (without the `.icns` extension). For example:
     ```xml
     <key>CFBundleIconFile</key>
     <string>OutlookAlerter</string>
     ```

3. **Configure `jpackage`**:
   - Update the `pom.xml` file to include the `--icon` argument in the `jpackage` configuration. For example:
     ```xml
     <argument>--icon</argument>
     <argument>${project.build.directory}/classes/OutlookAlerter.icns</argument>
     ```

4. **Verify Icon Placement**:
   - After building the app bundle, ensure the `.icns` file is located in the `Contents/Resources` directory of the `.app` bundle.

## 3. Testing the Icon

1. **Build the Project**:
   - Run the following command to build the project and create the app bundle:
     ```bash
     mvn clean package
     ```

2. **Check the Icon**:
   - Open the `.app` bundle in Finder and verify that the icon is displayed correctly.

## 4. Troubleshooting

- If the icon does not display correctly:
  1. Verify the `.icns` file is correctly placed in the `Contents/Resources` directory.
  2. Ensure the `CFBundleIconFile` key in the `Info.plist` file matches the `.icns` file name.
  3. Rebuild the project to ensure all changes are applied.

By following these steps, you can ensure the icon is properly created, managed, and included in the OutlookAlerter build.
