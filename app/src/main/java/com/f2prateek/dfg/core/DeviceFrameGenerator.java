/*
 * Copyright 2013 Prateek Srivastava (@f2prateek)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.f2prateek.dfg.core;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import com.f2prateek.dfg.model.Device;
import com.f2prateek.dfg.util.BitmapUtils;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

import static com.f2prateek.dfg.util.LogUtils.makeLogTag;

public class DeviceFrameGenerator {

    private static final String LOGTAG = makeLogTag(DeviceFrameGenerator.class);

    public static final String DFG_DIR_NAME = "/Device-Frame-Generator/";
    private static final String DFG_FILE_NAME_TEMPLATE = "DFG_%s.png";
    private static final String DFG_FILE_PATH_TEMPLATE = "%s/%s/%s";

    private Context mContext;
    private Callback mCallback;

    public DeviceFrameGenerator(Context context, Callback callback) {
        mContext = context;
        mCallback = callback;
    }

    /**
     * Generate the frame.
     *
     * @param device         Device to be drawn on
     * @param screenshotPath path to the screenshot file
     * @param withShadow     true if to be drawn with shadow
     * @param withGlare      true if to be drawn with glare
     */
    public void generateFrame(Device device, String screenshotPath, boolean withShadow,
                              boolean withGlare) {
        Log.i(LOGTAG, String.format("Generating for %s %s and %s from file %s.", device.getName(),
                withGlare ? " with glare " : " without glare ",
                withShadow ? " with shadow " : " without shadow ",
                screenshotPath));

        Bitmap screenshot;
        try {
            screenshot = BitmapUtils.decodeFile(screenshotPath);
        } catch (IOException e) {
            mCallback.notifyFailedOpenScreenshotError(screenshotPath);
            return;
        }
        generateFrame(device, screenshot, withShadow, withGlare);
    }

    /**
     * Generate the frame.
     *
     * @param withShadow true if to be drawn with shadow
     * @param withGlare  true if to be drawn with glare
     */
    private void generateFrame(Device device, Bitmap screenshot, boolean withShadow, boolean withGlare) {
        mCallback.notifyStarting(screenshot);
        String orientation = null;
        try {
            orientation = checkDimensions(device, screenshot);
        } catch (UnmatchedDimensionsException e) {
            mCallback.notifyUnmatchedDimensionsError(device, screenshot.getHeight(), screenshot.getWidth());
            Log.e(LOGTAG, e.toString());
            return;
        }

        Bitmap background;
        Bitmap glare;
        Bitmap shadow;
        try {
            background = BitmapUtils.decodeResource(mContext, device.getBackgroundString(orientation));
            glare = BitmapUtils.decodeResource(mContext, device.getGlareString(orientation));
            shadow = BitmapUtils.decodeResource(mContext, device.getShadowString(orientation));
        } catch (IOException e) {
            mCallback.notifyFailed();
            Log.e(LOGTAG, e.toString());
            return;
        }

        Canvas frame;
        if (withShadow) {
            frame = new Canvas(shadow);
            frame.drawBitmap(background, 0f, 0f, null);
        } else {
            frame = new Canvas(background);
        }

        final int[] offset;
        if (isPortrait(orientation)) {
            screenshot = Bitmap.createScaledBitmap(screenshot, device.getPortSize()[0],
                    device.getPortSize()[1], false);
            offset = device.getPortOffset();
        } else {
            screenshot = Bitmap.createScaledBitmap(screenshot, device.getPortSize()[1],
                    device.getPortSize()[0], false);
            offset = device.getLandOffset();
        }
        frame.drawBitmap(screenshot, offset[0], offset[1], null);

        if (withGlare) {
            frame.drawBitmap(glare, 0f, 0f, null);
        }

        ImageMetadata imageMetadata = prepareMetadata();
        // Save the screenshot to the MediaStore
        ContentValues values = new ContentValues();
        ContentResolver resolver = mContext.getContentResolver();
        values.put(MediaStore.Images.ImageColumns.DATA, imageMetadata.imageFilePath);
        values.put(MediaStore.Images.ImageColumns.TITLE, imageMetadata.imageFileName);
        values.put(MediaStore.Images.ImageColumns.DISPLAY_NAME, imageMetadata.imageFileName);
        values.put(MediaStore.Images.ImageColumns.DATE_TAKEN, imageMetadata.imageTime);
        values.put(MediaStore.Images.ImageColumns.DATE_ADDED, imageMetadata.imageTime);
        values.put(MediaStore.Images.ImageColumns.DATE_MODIFIED, imageMetadata.imageTime);
        values.put(MediaStore.Images.ImageColumns.MIME_TYPE, "image/png");
        Uri imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        try {
            OutputStream out = resolver.openOutputStream(imageUri);
            if (withShadow) {
                shadow.compress(Bitmap.CompressFormat.PNG, 100, out);
            } else {
                background.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            out.flush();
            out.close();
        } catch (IOException e) {
            mCallback.notifyFailed();
            Log.e(LOGTAG, e.toString());
            return;
        }

        // update file size in the database
        values.clear();
        values.put(MediaStore.Images.ImageColumns.SIZE, new File(imageMetadata.imageFilePath).length());
        resolver.update(imageUri, values, null, null);

        mCallback.notifyDone(imageUri);
    }

    // Views should have these methods to notify the user.
    public interface Callback {
        public void notifyStarting(Bitmap screenshot);

        public void notifyFailedOpenScreenshotError(String screenshotPath);

        public void notifyUnmatchedDimensionsError(Device device, int screenhotHeight, int screenshotWidth);

        public void notifyFailed();

        public void notifyDone(Uri imageUri);
    }

    public class ImageMetadata {
        String imageFileName;
        String imageFilePath;
        long imageTime;
    }

    /**
     * Prepare the metadata for our image.
     */
    private ImageMetadata prepareMetadata() {
        ImageMetadata imageMetadata = new ImageMetadata();
        imageMetadata.imageTime = System.currentTimeMillis();
        String imageDate = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date(imageMetadata.imageTime));
        String imageDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES).getAbsolutePath();
        imageMetadata.imageFileName = String.format(DFG_FILE_NAME_TEMPLATE, imageDate);
        imageMetadata.imageFilePath = String.format(DFG_FILE_PATH_TEMPLATE, imageDir,
                DFG_DIR_NAME, imageMetadata.imageFileName);
        return imageMetadata;
    }

    /**
     * Checks if screenshot matches the aspect ratio of the device.
     *
     * @param device     The Device to frame.
     * @param screenshot The screenshot to frame.
     * @return "port" if matched to portrait and "land" if matched to landscape
     * @throws UnmatchedDimensionsException If it could not match any orientation to the device.
     */
    public static String checkDimensions(Device device, Bitmap screenshot) throws UnmatchedDimensionsException {
        float aspect1 = (float) screenshot.getHeight() / (float) screenshot.getWidth();
        float aspect2 = (float) device.getPortSize()[1] / (float) device.getPortSize()[0];

        if (aspect1 == aspect2) {
            return "port";
        } else if (aspect1 == 1 / aspect2) {
            return "land";
        }

        Log.e(LOGTAG, String.format(
                "Screenshot height = %d, width = %d. Device height = %d, width = %d. Aspect1 = %f, Aspect 2 = %f",
                screenshot.getHeight(), screenshot.getWidth(), device.getPortSize()[1], device.getPortSize()[0],
                aspect1, aspect2));
        throw new UnmatchedDimensionsException(device, screenshot.getHeight(), screenshot.getWidth());
    }

    private static boolean isPortrait(String orientation) {
        return (orientation.compareTo("port") == 0);
    }

}