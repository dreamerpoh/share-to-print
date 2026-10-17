package com.csr.printbridge;

import android.graphics.Matrix;
import android.net.Uri;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.app.Activity;
import android.os.Bundle;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.xmlpull.v1.XmlPullParser;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private Uri pendingPrintUri = null;
    private String printPaper = "A4";
    private String printOrientation = "PORTRAIT";
    private float customPaperWidthMm = 210.0f;
    private float customPaperHeightMm = 297.0f;
    private String printScale = "100";
    private static final String ACTION_USB_PERMISSION =
            "com.csr.printbridge.USB_PERMISSION";

    private static final int EPSON_VID = 0x04B8;
    private static final int EPSON_PID = 0x1188;

    private static final int BRIDGE_PORT = 8765;

    private UsbManager usbManager;
    private TextView statusText;

    private ServerSocket serverSocket;
    private Thread serverThread;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }

            UsbDevice device;

            if (android.os.Build.VERSION.SDK_INT >= 33) {
                device = intent.getParcelableExtra(
                        UsbManager.EXTRA_DEVICE,
                        UsbDevice.class
                );
            } else {
                device = intent.getParcelableExtra(
                        UsbManager.EXTRA_DEVICE
                );
            }

            boolean granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED,
                    false
            );

            if (granted && device != null) {

                showStatus(
                        "USB permission granted.\n\n" +
                        "Bridge: http://127.0.0.1:" + BRIDGE_PORT
                );

            } else {

                showStatus("USB permission denied.");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        usbManager =
                (UsbManager) getSystemService(Context.USB_SERVICE);

        statusText = new TextView(this);
        statusText.setTextSize(18);
        statusText.setPadding(30, 50, 30, 30);

        statusText.setText(
                "CSR Print Bridge\n\n" +
                "Starting..."
        );

        setContentView(statusText);

        IntentFilter filter =
                new IntentFilter(ACTION_USB_PERMISSION);

        if (android.os.Build.VERSION.SDK_INT >= 33) {

            registerReceiver(
                    usbReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
            );

        } else {

            registerReceiver(
                    usbReceiver,
                    filter
            );
        }

        
	if (getIntent() == null
	        || !Intent.ACTION_SEND.equals(getIntent().getAction())) {

	    startBridgeServer();
	}

	findPrinter();

	handleSharedFile(getIntent());
        

        
    }

    private void showPrintSettings() {

        android.widget.LinearLayout layout =
                new android.widget.LinearLayout(this);

        layout.setOrientation(
                android.widget.LinearLayout.VERTICAL
        );

        int padding = 24;

        layout.setPadding(
                padding,
                padding,
                padding,
                padding
        );

        // PAPER
        android.widget.TextView paperLabel =
                new android.widget.TextView(this);

        paperLabel.setText("Paper Size");

        layout.addView(paperLabel);

        android.widget.Spinner paperSpinner =
                new android.widget.Spinner(this);

        String[] paperOptions = {
                "A4",
                "Letter",
		"Long 8.5 x 13",
		"Legal 8.5 x 14",
		"Custom"
        };

        android.widget.ArrayAdapter<String> paperAdapter =
                new android.widget.ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        paperOptions
                );

        paperSpinner.setAdapter(paperAdapter);

        paperSpinner.setSelection(
                printPaper.equals("Letter") ? 1 : 0
        );

        layout.addView(paperSpinner);


        // ORIENTATION
        android.widget.TextView orientationLabel =
                new android.widget.TextView(this);

        orientationLabel.setText("Orientation");

        layout.addView(orientationLabel);

        android.widget.Spinner orientationSpinner =
                new android.widget.Spinner(this);

        String[] orientationOptions = {
                "Portrait",
                "Landscape"
        };

        android.widget.ArrayAdapter<String>
                orientationAdapter =
                new android.widget.ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        orientationOptions
                );

        orientationSpinner.setAdapter(
                orientationAdapter
        );

        orientationSpinner.setSelection(
                printOrientation.equals("Landscape")
                        ? 1
                        : 0
        );

        layout.addView(orientationSpinner);


        // SCALE
        android.widget.TextView scaleLabel =
                new android.widget.TextView(this);

        scaleLabel.setText("Scale");

        layout.addView(scaleLabel);

        android.widget.Spinner scaleSpinner =
                new android.widget.Spinner(this);

        String[] scaleOptions = {
                "100% - Actual Size",
                "Fit to Page",
                "75%",
                "50%"
        };

        android.widget.ArrayAdapter<String>
                scaleAdapter =
                    new android.widget.ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        scaleOptions
                );

        scaleSpinner.setAdapter(scaleAdapter);

        int scalePosition = 0;

        if (printScale.equals("FIT")) {
            scalePosition = 1;
        } else if (printScale.equals("75")) {
            scalePosition = 2;
        } else if (printScale.equals("50")) {
            scalePosition = 3;
        }

        scaleSpinner.setSelection(scalePosition);

        layout.addView(scaleSpinner);

	android.widget.EditText customWidth =
	        new android.widget.EditText(this);

	customWidth.setHint("Width (mm)");
	customWidth.setInputType(
	        android.text.InputType.TYPE_CLASS_NUMBER |
	        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
	);

	customWidth.setText(
	        String.valueOf(customPaperWidthMm)
	);


	android.widget.EditText customHeight =
	        new android.widget.EditText(this);

	customHeight.setHint("Height (mm)");
	customHeight.setInputType(
	        android.text.InputType.TYPE_CLASS_NUMBER |
	        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
	);

	customHeight.setText(
	        String.valueOf(customPaperHeightMm)
	);


	layout.addView(customWidth);
	layout.addView(customHeight);


	// CUSTOM visibility
	boolean isCustomPaper =
	        "Custom".equals(printPaper);

	customWidth.setVisibility(
	        isCustomPaper
	                ? android.view.View.VISIBLE
	                : android.view.View.GONE
	);

	customHeight.setVisibility(
	        isCustomPaper
	                ? android.view.View.VISIBLE
	                : android.view.View.GONE
	);


	paperSpinner.setOnItemSelectedListener(
	        new android.widget.AdapterView.OnItemSelectedListener() {

	            @Override
	            public void onItemSelected(
	                    android.widget.AdapterView<?> parent,
	                    android.view.View view,
	                    int position,
	                    long id
	            ) {

	                String selected =
	                        parent.getItemAtPosition(position)
	                                .toString();

	                boolean custom =
	                        "Custom".equals(selected);

	                customWidth.setVisibility(
	                        custom
	                                ? android.view.View.VISIBLE
	                                : android.view.View.GONE
	                );

	                customHeight.setVisibility(
	                        custom
	                                ? android.view.View.VISIBLE
	                                : android.view.View.GONE
	                );
	            }

	            @Override
	            public void onNothingSelected(
	                    android.widget.AdapterView<?> parent
	            ) {
	            }
	        }
	);


        new android.app.AlertDialog.Builder(this)
                .setTitle("Print Settings")
                .setView(layout)
                .setNegativeButton(
                        "Cancel",
                        null
                )
                .setPositiveButton(
                        "Print",
                        (dialog, which) -> {

                            printPaper =
                                    paperSpinner
                                            .getSelectedItem()
                                            .toString();

                            printOrientation =
                                    orientationSpinner
                                            .getSelectedItem()
                                            .toString();

                            int selectedScale =
                                    scaleSpinner
                                            .getSelectedItemPosition();

                            if (selectedScale == 1) {
                                printScale = "FIT";
                            } else if (selectedScale == 2) {
                                printScale = "75";
                            } else if (selectedScale == 3) {
                                printScale = "50";
                            } else {
                                printScale = "100";
                            }

			    if ("Custom".equals(printPaper)) {

			        try {

			            customPaperWidthMm =
			                    Float.parseFloat(
			                            customWidth.getText()
			                                    .toString()
			                                    .trim()
			                    );

			            customPaperHeightMm =
			                    Float.parseFloat(
			                            customHeight.getText()
			                                    .toString()
			                                    .trim()
			                    );

			        } catch (Exception e) {

			            showStatus(
			                    "CUSTOM PAPER ERROR\n\n" +
			                    "Enter valid width and height."
			            );

			            return;
			        }
			    }

                            printPendingFile();
			    /*showStatus(
			            "SETTINGS SAVED\n\n" +
			            printPaper + "\n" +
			            printOrientation + "\n" +
			            printScale
			    );*/
                        }
                )
                .show();
    }

	private void testEscpR() {

	    try {

	        ByteArrayOutputStream data =
	                new ByteArrayOutputStream();

	        // Enter ESC/P-R mode
	        data.write(0x1B);
	        data.write(0x28);
	        data.write(0x52);

	        data.write(0x06);
	        data.write(0x00);
	        data.write(0x00);
	        data.write(0x00);

	        data.write('E');
	        data.write('S');
	        data.write('C');
	        data.write('P');
	        data.write('R');


	        // setj
	        data.write(0x1B);
	        data.write('j');

	        data.write(0x16);
	        data.write(0x00);
	        data.write(0x00);
	        data.write(0x00);

	        data.write('s');
	        data.write('e');
	        data.write('t');
	        data.write('j');

	        // A4 @ 360 dpi
	        int paperWidth =
	                (int) Math.ceil(210.0 / 25.4 * 360);

	        int paperHeight =
	                (int) Math.ceil(297.0 / 25.4 * 360);

	        int printableWidth = paperWidth;
	        int printableHeight = paperHeight;

	        data.write(
	                (paperWidth >> 24) & 0xFF
	        );
	        data.write(
	                (paperWidth >> 16) & 0xFF
	        );
	        data.write(
	                (paperWidth >> 8) & 0xFF
	        );
	        data.write(
	                paperWidth & 0xFF
	        );

	        data.write(
	                (paperHeight >> 24) & 0xFF
	        );
	        data.write(
	                (paperHeight >> 16) & 0xFF
	        );
	        data.write(
	                (paperHeight >> 8) & 0xFF
	        );
	        data.write(
	                paperHeight & 0xFF
	        );

	        // top + left margins
	        data.write(0x00);
	        data.write(0x00);
	        data.write(0x00);
	        data.write(0x00);

	        data.write(
	                (printableWidth >> 24) & 0xFF
	        );
	        data.write(
	                (printableWidth >> 16) & 0xFF
	        );
	        data.write(
	                (printableWidth >> 8) & 0xFF
	        );
	        data.write(
	                printableWidth & 0xFF
	        );

	        data.write(
	                (printableHeight >> 24) & 0xFF
	        );
	        data.write(
	                (printableHeight >> 16) & 0xFF
	        );
	        data.write(
	                (printableHeight >> 8) & 0xFF
	        );
	        data.write(
	                printableHeight & 0xFF
	        );

	        // 360 dpi mode
	        data.write(0x00);

	        // bidirectional
	        data.write(0x00);


	        // Start page
	        data.write(0x1B);
	        data.write('p');

	        data.write(0x00);
	        data.write(0x00);
	        data.write(0x00);
	        data.write(0x00);

	        data.write('s');
	        data.write('t');
	        data.write('t');
	        data.write('p');


	        // End page
	        data.write(0x1B);
	        data.write('p');

	        data.write(0x01);
	        data.write(0x00);
	        data.write(0x00);
	        data.write(0x00);

	        data.write('e');
	        data.write('n');
	        data.write('d');
	        data.write('p');

	        data.write(0x00);


	        // End job
	        data.write(0x1B);
	        data.write('j');

	        data.write(0x00);
	        data.write(0x00);
	        data.write(0x00);
	        data.write(0x00);

	        data.write('e');
	        data.write('n');
	        data.write('d');
	        data.write('j');


	        int result =
	                printRaw(
	                        data.toByteArray()
	                );

	        showStatus(
	                result > 0
	                        ? "ESC/P-R TEST SENT"
	                        : "ESC/P-R TEST FAILED"
	        );

	    } catch (Exception e) {

	        showStatus(
	                "ESC/P-R ERROR\n\n" +
	                e.getMessage()
	        );
	    }
	}

	private boolean printPdfEscpR(Uri uri) {

	    try {

	        ParcelFileDescriptor fileDescriptor =
	                getContentResolver()
	                        .openFileDescriptor(uri, "r");

	        if (fileDescriptor == null) {
	            showStatus("ESC/P-R ERROR\n\nCannot open PDF.");
	            return false;
	        }

	        PdfRenderer renderer =
	                new PdfRenderer(fileDescriptor);

	        if (renderer.getPageCount() == 0) {

	            renderer.close();
	            fileDescriptor.close();

	            showStatus("ESC/P-R ERROR\n\nPDF has no pages.");
	            return false;
	        }

	        PdfRenderer.Page page =
	                renderer.openPage(0);

	        /*
	         * Epson ESC/P-R A4 @ 360 DPI
	         *
	         * Paper:
	         * 2976 x 4209
	         *
	         * Printable:
	         * 2892 x 4125
	         */

	        /*final int paperWidth = 2976;
	        final int paperHeight = 4209;

	        final int printableWidth = 2892;
	        final int printableHeight = 4125;

	        final int leftMargin = 42;
	        final int topMargin = 42;*/

		int paperWidth = 2976;
		int paperHeight = 4209;
		int printableWidth = 2892;
		int printableHeight = 4125;
		int leftMargin = 42;
		int topMargin = 42;


		if ("Long 8.5 x 13".equals(printPaper)) {

		    paperWidth = 3060;
		    paperHeight = 4680;

		    // 42 px margin sa bawat side
		    printableWidth = 2976;
		    printableHeight = 4596;

		    leftMargin = 42;
		    topMargin = 42;
		}

		if ("Legal 8.5 x 14".equals(printPaper)) {

		    paperWidth = 3060;
		    paperHeight = 5040;

		    printableWidth = 2976;
		    printableHeight = 4956;

		    leftMargin = 42;
		    topMargin = 42;
		}

		if ("Custom".equals(printPaper)) {

		    paperWidth =
		            Math.round(
		                    customPaperWidthMm
		                    / 25.4f
		                    * 360f
		            );

		    paperHeight =
		            Math.round(
		                    customPaperHeightMm
		                    / 25.4f
		                    * 360f
		            );

		    printableWidth =
		            Math.max(
		                    1,
		                    paperWidth - 84
		            );

		    printableHeight =
		            Math.max(
		                    1,
		                    paperHeight - 84
		            );

		    leftMargin = 42;
		    topMargin = 42;
		}




		Bitmap bitmap =
		        Bitmap.createBitmap(
		                paperWidth,
		                paperHeight,
		                Bitmap.Config.ARGB_8888
		        );

		bitmap.eraseColor(0xFFFFFFFF);

                if ("FIT".equals(printScale)) {

                    float sourceWidth = page.getWidth();
                    float sourceHeight = page.getHeight();

                    float scaleX =
                            (float) printableWidth /
                            sourceWidth;

                    float scaleY =
                            (float) printableHeight /
                            sourceHeight;

                    float scale =
                            Math.min(scaleX, scaleY);

                    float renderedWidth =
                            sourceWidth * scale;

                    float renderedHeight =
                            sourceHeight * scale;

                    float offsetX =
                            leftMargin +
                            (printableWidth - renderedWidth) / 2f;

                    float offsetY =
                            topMargin +
                            (printableHeight - renderedHeight) / 2f;

                    Matrix matrix =
                            new Matrix();

                    if ("Landscape".equals(printOrientation)) {

                        /*
                         * Rotate PDF page 90 degrees clockwise
                         * then place it inside the landscape paper.
                         */

                        matrix.postRotate(
                                90,
                                sourceWidth / 2f,
                                sourceHeight / 2f
                        );

                        matrix.postScale(
                                scale,
                                scale
                        );

                        float rotatedWidth =
                                sourceHeight * scale;

                        float rotatedHeight =
                                sourceWidth * scale;

                        float landscapeOffsetX =
                                leftMargin +
                                (printableWidth - rotatedWidth) / 2f;

                        float landscapeOffsetY =
                                topMargin +
                                (printableHeight - rotatedHeight) / 2f;

                        matrix.postTranslate(
                                landscapeOffsetX,
                                landscapeOffsetY
                        );

                    } else {

                        matrix.setScale(
                                scale,
                                scale
                        );

                        matrix.postTranslate(
                                offsetX,
                                offsetY
                        );
                    }

                    page.render(
                            bitmap,
                            null,
                            matrix,
                            PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                    );

		} else {


		    if ("Landscape".equals(printOrientation)) {

		        Bitmap portraitBitmap =
		                Bitmap.createBitmap(
		                        paperHeight,
		                        paperWidth,
		                        Bitmap.Config.ARGB_8888
		                );

		        portraitBitmap.eraseColor(0xFFFFFFFF);

		        page.render(
		                portraitBitmap,
		                null,
		                null,
		                PdfRenderer.Page.RENDER_MODE_FOR_PRINT
		        );

		        android.graphics.Canvas canvas =
		                new android.graphics.Canvas(bitmap);

		        canvas.drawColor(0xFFFFFFFF);

		        canvas.translate(
		                paperWidth,
		                0
		        );

		        canvas.rotate(90);

		        canvas.drawBitmap(
		                portraitBitmap,
		                0,
		                0,
		                null
		        );

		        portraitBitmap.recycle();

		    } else {

		        page.render(
		                bitmap,
		                null,
		                null,
		                PdfRenderer.Page.RENDER_MODE_FOR_PRINT
		        );

		    }
		}

                    Matrix matrix =
                            new Matrix();


		
	        page.close();
	        renderer.close();
	        fileDescriptor.close();

	        /*
	         * =====================================================
	         * ESC/P-R START JOB
	         * =====================================================
	         */

	        ByteArrayOutputStream out =
	                new ByteArrayOutputStream();

	        // Exit Remote Mode
	        out.write(0x1B);
	        out.write(0x00);
	        out.write(0x00);
	        out.write(0x00);

	        // ESC/P-R Mode
	        out.write(0x1B);
	        out.write(0x28);
	        out.write(0x52);
	        out.write(0x06);
	        out.write(0x00);
	        out.write(0x00);

	        out.write('E');
	        out.write('S');
	        out.write('C');
	        out.write('P');
	        out.write('R');

	        /*
	         * =====================================================
	         * setq - Print Quality
	         *
	         * mediaType     = 0
	         * quality       = 1 (NORMAL)
	         * colorMode     = 0
	         * brightness    = 0
	         * contrast      = 0
	         * saturation    = 0
	         * colorPlane    = 0 (RGB)
	         * paletteSize   = 0
	         * =====================================================
	         */

	        out.write(0x1B);
	        out.write('q');
	        out.write(0x09);
	        out.write(0x00);
	        out.write(0x00);
	        out.write(0x00);

	        out.write('s');
	        out.write('e');
	        out.write('t');
	        out.write('q');

	        out.write(0x00); // media type
	        out.write(0x01); // normal quality
	        out.write(0x00); // color mode
	        out.write(0x00); // brightness
	        out.write(0x00); // contrast
	        out.write(0x00); // saturation
	        out.write(0x00); // RGB

	        out.write(0x00); // palette size high
	        out.write(0x00); // palette size low

	        /*
	         * =====================================================
	         * chku
	         * =====================================================
	         */

	        out.write(0x1B);
	        out.write('u');
	        out.write(0x02);
	        out.write(0x00);
	        out.write(0x00);
	        out.write(0x00);

	        out.write('c');
	        out.write('h');
	        out.write('k');
	        out.write('u');

	        out.write(0x01);
	        out.write(0x01);

	        /*
	         * =====================================================
	         * setj - Job
	         * =====================================================
	         */

	        out.write(0x1B);
	        out.write('j');
	        out.write(0x16);
	        out.write(0x00);
	        out.write(0x00);
	        out.write(0x00);

	        out.write('s');
	        out.write('e');
	        out.write('t');
	        out.write('j');

	        // paper width - BIG ENDIAN
	        out.write((paperWidth >> 24) & 0xFF);
	        out.write((paperWidth >> 16) & 0xFF);
	        out.write((paperWidth >> 8) & 0xFF);
	        out.write(paperWidth & 0xFF);

	        // paper height - BIG ENDIAN
	        out.write((paperHeight >> 24) & 0xFF);
	        out.write((paperHeight >> 16) & 0xFF);
	        out.write((paperHeight >> 8) & 0xFF);
	        out.write(paperHeight & 0xFF);

	        // top margin
	        out.write((topMargin >> 8) & 0xFF);
	        out.write(topMargin & 0xFF);

	        // left margin
	        out.write((leftMargin >> 8) & 0xFF);
	        out.write(leftMargin & 0xFF);

	        // printable width
	        out.write((printableWidth >> 24) & 0xFF);
	        out.write((printableWidth >> 16) & 0xFF);
	        out.write((printableWidth >> 8) & 0xFF);
	        out.write(printableWidth & 0xFF);

	        // printable height
	        out.write((printableHeight >> 24) & 0xFF);
	        out.write((printableHeight >> 16) & 0xFF);
	        out.write((printableHeight >> 8) & 0xFF);
	        out.write(printableHeight & 0xFF);

	        // 360 x 360 DPI
	        out.write(0x00);

	        // print direction
	        out.write(0x00);

	        /*
	         * =====================================================
	         * sttp - Start Page
	         * =====================================================
	         */

	        out.write(0x1B);
	        out.write('p');
	        out.write(0x00);
	        out.write(0x00);
	        out.write(0x00);
	        out.write(0x00);

	        out.write('s');
	        out.write('t');
	        out.write('t');
	        out.write('p');


	        /*
	         * =====================================================
	         * RGB RASTER
	         * =====================================================
	         */

	        final int bytesPerPixel = 3;

	        for (int y = 0; y < printableHeight; y++) {

	            int sourceY =
	                    topMargin + y;

	            ByteArrayOutputStream rgb =
	                    new ByteArrayOutputStream(
	                            printableWidth * bytesPerPixel
	                    );

	            int x = 0;

	            while (x < printableWidth) {

	                int sourceX =
	                        leftMargin + x;

	                int pixel =
	                        bitmap.getPixel(
	                                sourceX,
	                                sourceY
	                        );

	                int red =
	                        (pixel >> 16) & 0xFF;

	                int green =
	                        (pixel >> 8) & 0xFF;

	                int blue =
	                        pixel & 0xFF;

	                int run = 1;

	                while (run < 128
	                        && x + run < printableWidth) {

	                    int nextPixel =
	                            bitmap.getPixel(
	                                    leftMargin + x + run,
	                                    sourceY
	                            );

	                    int nr =
	                            (nextPixel >> 16) & 0xFF;

	                    int ng =
	                            (nextPixel >> 8) & 0xFF;

	                    int nb =
	                            nextPixel & 0xFF;

	                    if (nr != red
	                            || ng != green
	                            || nb != blue) {
	                        break;
	                    }

	                    run++;
	                }

	                if (run >= 2) {

	                    /*
	                     * Epson RLE repeat
	                     *
	                     * FF - count + 2
	                     */
	                    rgb.write(
	                            0xFF - run + 2
	                    );

	                    rgb.write(red);
	                    rgb.write(green);
	                    rgb.write(blue);

	                    x += run;

	                } else {

	                    int startX = x;

	                    x++;

	                    while (
	                            x < printableWidth
	                            && (x - startX) < 128
	                    ) {

	                        if (x + 1 < printableWidth) {

	                            int p1 =
	                                    bitmap.getPixel(
	                                            leftMargin + x,
	                                            sourceY
	                                    );

	                            int p2 =
	                                    bitmap.getPixel(
	                                            leftMargin + x + 1,
	                                            sourceY
	                                    );

	                            if (p1 == p2) {
	                                break;
	                            }
	                        }

	                        x++;
	                    }

	                    int count =
	                            x - startX;

	                    rgb.write(count - 1);

	                    for (int i = 0;
	                         i < count;
	                         i++) {

	                        int p =
	                                bitmap.getPixel(
	                                        leftMargin +
	                                                startX + i,
	                                        sourceY
	                                );

	                        rgb.write(
	                                (p >> 16) & 0xFF
	                        );

	                        rgb.write(
	                                (p >> 8) & 0xFF
	                        );

	                        rgb.write(
	                                p & 0xFF
	                        );
	                    }
	                }
	            }

	            byte[] compressed =
	                    rgb.toByteArray();

	            /*
	             * =================================================
	             * dsnd
	             *
	             * Parameter length =
	             * 7 + raster data size
	             * =================================================
	             */

	            int parameterSize =
	                    7 + compressed.length;

	            out.write(0x1B);
	            out.write('d');

	            // parameter length - LITTLE ENDIAN
	            out.write(parameterSize & 0xFF);
	            out.write((parameterSize >> 8) & 0xFF);
	            out.write((parameterSize >> 16) & 0xFF);
	            out.write((parameterSize >> 24) & 0xFF);

	            out.write('d');
	            out.write('s');
	            out.write('n');
	            out.write('d');

	            // X - BIG ENDIAN
	            out.write((0 >> 8) & 0xFF);
	            out.write(0 & 0xFF);

	            // Y - BIG ENDIAN
	            out.write((y + topMargin >> 8) & 0xFF);
	            out.write((y + topMargin) & 0xFF);

	            // RLE
	            out.write(0x01);

	            // raster size - BIG ENDIAN
	            out.write((compressed.length >> 8) & 0xFF);
	            out.write(compressed.length & 0xFF);

	            out.write(compressed);
	        }


	        /*
	         * =====================================================
	         * END PAGE
	         * =====================================================
	         */

	        out.write(0x1B);
	        out.write('p');
	        out.write(0x01);
	        out.write(0x00);
	        out.write(0x00);
	        out.write(0x00);

	        out.write('e');
	        out.write('n');
	        out.write('d');
	        out.write('p');

	        out.write(0x00);


	        /*
	         * =====================================================
	         * END JOB
	         * =====================================================
	         */

	        out.write(0x1B);
	        out.write('j');
	        out.write(0x00);
	        out.write(0x00);
	        out.write(0x00);
	        out.write(0x00);

	        out.write('e');
	        out.write('n');
	        out.write('d');
	        out.write('j');


	        bitmap.recycle();

	        byte[] job =
	                out.toByteArray();

	        int result =
	                //printRaw(job);
			printRawChunked(job);

	        /*boolean ok =
	                result > 0;

	        showStatus(
	                ok
	                        ? "ESC/P-R PRINT SUCCESS\n\n" +
	                          "A4 360 DPI"
	                        : "ESC/P-R PRINT FAILED"
	        );*/

		boolean ok =
		        result > 0;

		showStatus(
		        "ESC/P-R RESULT\n\n" +
		        "Bytes sent: " + result +
		        "\nJob size: " + job.length
		);
	        return ok;

	    } catch (Exception e) {

	        showStatus(
	                "ESC/P-R ERROR\n\n" +
	                e.getMessage()
	        );

	        return false;
	    }
	}

	private int printRawChunked(byte[] data) {

	    UsbDevice device = null;

	    for (UsbDevice d :
	            usbManager.getDeviceList().values()) {

	        if (d.getVendorId() == EPSON_VID
	                && d.getProductId() == EPSON_PID) {

	            device = d;
	            break;
	        }
	    }

	    if (device == null) {
	        return -1;
	    }

	    UsbInterface printerInterface = null;
	    UsbEndpoint outEndpoint = null;

	    for (int i = 0;
	         i < device.getInterfaceCount();
	         i++) {

	        UsbInterface usbInterface =
	                device.getInterface(i);

	        if (usbInterface.getId() == 1) {

	            printerInterface =
	                    usbInterface;

	            for (int j = 0;
	                 j < usbInterface.getEndpointCount();
	                 j++) {

	                UsbEndpoint endpoint =
	                        usbInterface.getEndpoint(j);

	                if (endpoint.getDirection()
	                        == UsbConstants.USB_DIR_OUT
	                        &&
	                        endpoint.getType()
	                        == UsbConstants.USB_ENDPOINT_XFER_BULK) {

	                    outEndpoint = endpoint;
	                    break;
	                }
	            }

	            break;
	        }
	    }

	    if (printerInterface == null
	            || outEndpoint == null) {

	        return -1;
	    }

	    if (!usbManager.hasPermission(device)) {
	        return -1;
	    }

	    UsbDeviceConnection connection =
	            usbManager.openDevice(device);

	    if (connection == null) {
	        return -1;
	    }

	    boolean claimed =
	            connection.claimInterface(
	                    printerInterface,
	                    true
	            );

	    if (!claimed) {

	        connection.close();
	        return -1;
	    }

	    try {

	        int totalSent = 0;

	        final int chunkSize = 4096;

	        while (totalSent < data.length) {

	            int remaining =
	                    data.length - totalSent;

	            int sendSize =
	                    Math.min(
	                            chunkSize,
	                            remaining
	                    );

	            byte[] chunk =
	                    new byte[sendSize];

	            System.arraycopy(
	                    data,
	                    totalSent,
	                    chunk,
	                    0,
	                    sendSize
	            );

	            int sent =
	                    connection.bulkTransfer(
	                            outEndpoint,
	                            chunk,
	                            sendSize,
	                            10000
	                    );

	            if (sent != sendSize) {
	                return -1;
	            }

	            totalSent += sent;
	        }

	        return totalSent;

	    } finally {

	        connection.releaseInterface(
	                printerInterface
	        );

	        connection.close();
	    }
	}
	private void printPendingFile() {

	    if (pendingPrintUri == null) {
	        showStatus(
	                "PDF ERROR\n\n" +
	                "No file selected."
	        );
	        return;
	    }

	    Uri uri = pendingPrintUri;
	    String mimeType =
	            getContentResolver()
	                    .getType(uri);

	    if ("application/pdf".equals(mimeType)) {

	        printPdfEscpR(uri);
	        return;
	    }

	    try {

	        ParcelFileDescriptor fileDescriptor =
	                getContentResolver()
	                        .openFileDescriptor(uri, "r");

	        if (fileDescriptor == null) {
	            showStatus(
	                    "PDF ERROR\n\n" +
	                    "Cannot open PDF."
	            );
	            return;
	        }

	        PdfRenderer renderer =
	                new PdfRenderer(fileDescriptor);

	        if (renderer.getPageCount() == 0) {

	            renderer.close();
	            fileDescriptor.close();

	            showStatus(
	                    "PDF ERROR\n\n" +
	                    "PDF has no pages."
	            );
	            return;
	        }

	        PdfRenderer.Page page =
	                renderer.openPage(0);

	        
		
		
		
		int pdfWidth = page.getWidth();
		int pdfHeight = page.getHeight();

		Bitmap bitmap =
		        Bitmap.createBitmap(
		                pdfWidth,
		                pdfHeight,
		                Bitmap.Config.ARGB_8888
		        );
		

	        bitmap.eraseColor(0xFFFFFFFF);

	        page.render(
	                bitmap,
	                null,
	                null,
	                PdfRenderer.Page.RENDER_MODE_FOR_PRINT
	        );

	        page.close();
	        renderer.close();
	        fileDescriptor.close();

	        int width = bitmap.getWidth();
	        int height = bitmap.getHeight();

	        int bytesPerRow =
	                (width + 7) / 8;

	        byte[] raster =
	                new byte[bytesPerRow * height];

	        for (int y = 0; y < height; y++) {

	            for (int x = 0; x < width; x++) {

	                int pixel =
	                        bitmap.getPixel(x, y);

	                int red =
	                        (pixel >> 16) & 0xFF;

	                int green =
	                        (pixel >> 8) & 0xFF;

	                int blue =
	                        pixel & 0xFF;

	                int gray =
	                        (red * 299 +
	                         green * 587 +
	                         blue * 114) / 1000;

	                if (gray < 128) {

	                    int index =
	                            y * bytesPerRow +
	                            (x / 8);

	                    raster[index] |=
	                            (byte) (0x80 >> (x % 8));
	                }
	            }
	        }

	        ByteArrayOutputStream printData =
	                new ByteArrayOutputStream();

	        printData.write(0x1B);
	        printData.write(0x40);

	        printData.write(0x1B);
	        printData.write(0x28);
	        printData.write(0x47);
	        printData.write(0x01);
	        printData.write(0x00);
	        printData.write(0x01);

	        printData.write(0x1B);
	        printData.write(0x2B);
	        printData.write(24);

	        int bandHeight = 24;

	        for (int y = 0; y < height; y += bandHeight) {

	            printData.write(0x1B);
	            printData.write(0x2E);

	            printData.write(0);
	            printData.write(10);
	            printData.write(10);
	            printData.write(24);


	            printData.write(width & 0xFF);
	            printData.write(
	                    (width >> 8) & 0xFF
	            );

	            for (int row = 0;
	                 row < bandHeight;
	                 row++) {

	                int sourceY = y + row;

	                if (sourceY < height) {

	                    int rowStart =
	                            sourceY * bytesPerRow;

	                    for (int x = 0;
	                         x < bytesPerRow;
	                         x++) {

	                        printData.write(
	                                raster[rowStart + x]
	                                        & 0xFF
	                        );
	                    }

	                } else {

	                    for (int x = 0;
	                         x < bytesPerRow;
	                         x++) {

	                        printData.write(0x00);
	                    }
	                }
	            }

	            printData.write(0x0D);
	            printData.write(0x0A);
	        }

	        printData.write(0x0C);

	        printData.write(0x1B);
	        printData.write(0x40);

	        int printResult =
	                printRaw(
	                        printData.toByteArray()
	                );

	        boolean printOk =
	                printResult > 0;

	        bitmap.recycle();

	        showStatus(
	                printOk
	                        ? "PDF PRINT SUCCESS\n\n" +
	                          "First page printed."
	                        : "PDF PRINT FAILED"
	        );

	        pendingPrintUri = null;

	    } catch (Exception e) {

	        showStatus(
	                "PDF ERROR\n\n" +
	                e.getMessage()
	        );
	    }
	}

	private String readXlsxTest(android.net.Uri uri) {

	    try {

	        InputStream input =
	                getContentResolver()
	                        .openInputStream(uri);

	        if (input == null) {
	            return "Cannot open XLSX file.";
	        }

	        ZipInputStream zip =
	                new ZipInputStream(input);

	        ZipEntry entry;

	        java.util.ArrayList<String> sharedStrings =
	                new java.util.ArrayList<>();

	        StringBuilder result =
	                new StringBuilder();

	        //result.append("XLSX CELLS\n\n");
                result.append("XLSX TEST\n\n");

	        /*
	         * READ SHARED STRINGS
	         */
		while ((entry = zip.getNextEntry()) != null) {
		    if (entry.getName()
		            .toLowerCase()
		            .contains("shared")) {

		        XmlPullParser parser =
		                android.util.Xml.newPullParser();

		        parser.setInput(
		                zip,
		                "UTF-8"
		        );

		        int eventType =
		                parser.getEventType();

		        while (eventType !=
		                XmlPullParser.END_DOCUMENT) {

		            if (eventType ==
		                    XmlPullParser.START_TAG
		                    &&
		                    parser.getName().equals("t")) {

		                String text =
		                        parser.nextText();

		                sharedStrings.add(text);
		            }

		            eventType =
		                    parser.next();
		        }

		        break;
		    }

		    /*if (entry.getName().toLowerCase().contains("shared")) {
			    XmlPullParser parser =
		        	    android.util.Xml.newPullParser();

			    parser.setInput(
			            zip,
			            "UTF-8"
		    );

		    int eventType =
		            parser.getEventType();

		    while (eventType !=
		            XmlPullParser.END_DOCUMENT) {

		        if (eventType ==
		                XmlPullParser.START_TAG
		                &&
		                parser.getName().equals("t")) {

		            String text =
	                    parser.nextText();

		            sharedStrings.add(text);
		        }

		        eventType =
		                parser.next();
		    }

		    break;
		}*/

		if (entry.getName().toLowerCase().contains("shared")) {
		    //if (entry.getName().equals(
		      //      "xl/sharedstring.xml")) {

		        ByteArrayOutputStream output =
		                new ByteArrayOutputStream();

		        byte[] buffer = new byte[1024];

		        int length;

		        while ((length = zip.read(buffer)) != -1) {

		            output.write(
		                    buffer,
		                    0,
		                    length
		            );
		        }

		        String xml =
		                output.toString("UTF-8");

		        result.append(
		                "SHARED STRING XML\n\n"
		        );

		        if (xml.length() > 1500) {

		            xml =
		                    xml.substring(0, 1500)
		                    + "\n...[CUT]...";
		        }

		        result.append(xml);

		        break;
		    }

	            /*if (entry.getName().equals(
	                    "xl/sharedstring.xml")) {

	                XmlPullParser parser =
	                        android.util.Xml.newPullParser();

	                parser.setInput(
	                        zip,
	                        "UTF-8"
	                );

	                int eventType =
	                        parser.getEventType();

	                boolean insideString = false;

	                StringBuilder text =
	                        new StringBuilder();

	                while (eventType !=
	                        XmlPullParser.END_DOCUMENT) {

	                    if (eventType ==
	                            XmlPullParser.START_TAG) {

	                        String tag =
	                                parser.getName();

	                        if (tag.equals("si")) {

	                            insideString = true;

	                            text.setLength(0);
	                        }

	                        if (insideString
	                                && tag.equals("t")) {

	                            text.append(
	                                    parser.nextText()
	                            );
	                        }
	                    }

	                    if (eventType ==
	                            XmlPullParser.END_TAG) {

	                        if (parser.getName()
	                                .equals("si")) {

	                            sharedStrings.add(
	                                    text.toString()
	                            );

	                            insideString = false;
	                        }
	                    }

	                    eventType =
	                            parser.next();
	                }

	                break;
	            }*/

	            zip.closeEntry();
	        }

		result.append("Shared strings: ")
		        .append(sharedStrings.size())
		        .append("\n\n");

		for (int i = 0;
		     i < sharedStrings.size() && i < 5;
		     i++) {

		    result.append(i)
		            .append(" = ")
		            .append(sharedStrings.get(i))
		            .append("\n");
		}

	        zip.close();
	        input.close();

	        /*
	         * OPEN XLSX AGAIN
	         */
	        input =
	                getContentResolver()
	                        .openInputStream(uri);

	        zip =
	                new ZipInputStream(input);

	        /*
	         * READ WORKSHEET
	         */
	        while ((entry = zip.getNextEntry()) != null) {

	            if (entry.getName().equals(
	                    "xl/worksheets/sheet1.xml")) {

	                XmlPullParser parser =
	                        android.util.Xml.newPullParser();

	                parser.setInput(
	                        zip,
	                        "UTF-8"
	                );

	                String cellRef = "";
	                String cellValue = "";
	                String cellType = "";

	                int eventType =
	                        parser.getEventType();

	                while (eventType !=
	                        XmlPullParser.END_DOCUMENT) {

	                    if (eventType ==
	                            XmlPullParser.START_TAG) {

	                        String tag =
	                                parser.getName();

	                        if (tag.equals("c")) {

	                            cellRef =
	                                    parser.getAttributeValue(
	                                            null,
	                                            "r"
	                                    );

	                            cellType =
	                                    parser.getAttributeValue(
	                                            null,
	                                            "t"
	                                    );
	                        }

	                        if (tag.equals("v")) {

	                            cellValue =
	                                    parser.nextText();

	                            if (!cellRef.isEmpty()) {

	                                String displayValue =
	                                        cellValue;

	                                if ("s".equals(cellType)) {

	                                    try {

	                                        int index =
	                                                Integer.parseInt(
	                                                        cellValue
	                                                );

	                                        if (index >= 0
	                                                && index <
	                                                sharedStrings.size()) {

	                                            displayValue =
	                                                    sharedStrings
	                                                            .get(index);
	                                        }

	                                    } catch (Exception ignored) {
	                                    }
	                                }

	                                result.append(
	                                        cellRef
	                                )
	                                .append(" = ")
	                                .append(displayValue)
	                                .append("\n");

	                                cellRef = "";
	                                cellValue = "";
	                                cellType = "";
	                            }
	                        }
	                    }

	                    eventType =
	                            parser.next();
	                }

	                break;
	            }

	            zip.closeEntry();
	        }

	        zip.close();
	        input.close();

	        return result.toString();

	    } catch (Exception e) {

	        return "XLSX ERROR\n\n" +
	                e.getMessage();
	    }
	}

	/*private String readXlsxTest(android.net.Uri uri) {

	    try {

	        InputStream input =
	                getContentResolver()
	                        .openInputStream(uri);

	        if (input == null) {
	            return "Cannot open XLSX file.";
	        }

	        ZipInputStream zip =
	                new ZipInputStream(input);

	        ZipEntry entry;

	        StringBuilder result =
	                new StringBuilder();

	        result.append("XLSX CELLS\n\n");

	        while ((entry = zip.getNextEntry()) != null) {

	            if (entry.getName().equals(
	                    "xl/worksheets/sheet1.xml")) {

	                XmlPullParser parser =
	                        android.util.Xml.newPullParser();

	                parser.setInput(
	                        zip,
	                        "UTF-8"
	                );

	                String cellRef = "";
	                String cellValue = "";

	                int eventType =
	                        parser.getEventType();

	                while (eventType !=
	                        XmlPullParser.END_DOCUMENT) {

	                    if (eventType ==
	                            XmlPullParser.START_TAG) {

	                        String tag =
	                                parser.getName();

	                        if (tag.equals("c")) {

	                            cellRef =
	                                    parser.getAttributeValue(
	                                            null,
	                                            "r"
	                                    );
	                        }

	                        if (tag.equals("v")) {

	                            cellValue =
	                                    parser.nextText();

	                            if (!cellRef.isEmpty()) {

	                                result.append(cellRef)
	                                        .append(" = ")
	                                        .append(cellValue)
	                                        .append("\n");

	                                cellRef = "";
	                                cellValue = "";
	                            }
	                        }
	                    }

	                    eventType =
	                            parser.next();
	                }

	                break;
	            }

	            zip.closeEntry();
	        }

	        zip.close();
	        input.close();

	        return result.toString();

	    } catch (Exception e) {

	        return "XLSX ERROR\n\n" +
	                e.getMessage();
	    }
	}

	/*private String readXlsxTest(android.net.Uri uri) {

	    try {

	        InputStream input =
	                getContentResolver()
	                        .openInputStream(uri);

	        if (input == null) {
	            return "Cannot open XLSX file.";
	        }

	        ZipInputStream zip =
	                new ZipInputStream(input);

	        ZipEntry entry;

	        StringBuilder result =
	                new StringBuilder();

	        while ((entry = zip.getNextEntry()) != null) {

	            String name =
	                    entry.getName();

	            if (name.equals("xl/workbook.xml")
	                    || name.equals("xl/worksheets/sheet1.xml")
	                    || name.equals("xl/sharedstring.xml")) {

	                ByteArrayOutputStream output =
	                        new ByteArrayOutputStream();

	                byte[] buffer =
	                        new byte[1024];

	                int length;

	                while ((length =
	                        zip.read(buffer)) != -1) {

	                    output.write(
	                            buffer,
	                            0,
	                            length
	                    );
	                }

	                String xml =
	                        output.toString("UTF-8");

	                result.append("\n\n===== ")
	                        .append(name)
	                        .append(" =====\n\n");

	                if (xml.length() > 2500) {
	                    xml =
	                            xml.substring(0, 2500)
	                            + "\n...[CUT]...";
	                }

	                result.append(xml);
	            }

	            zip.closeEntry();
	        }

	        zip.close();
	        input.close();

	        return "XLSX XML TEST" +
	                result.toString();

	    } catch (Exception e) {

	        return "XLSX ERROR\n\n" +
	                e.getMessage();
	    }
	}*/

	/*private String readXlsxTest(android.net.Uri uri) {

	    try {

	        InputStream input =
	                getContentResolver()
	                        .openInputStream(uri);

	        if (input == null) {
	            return "Cannot open XLSX file.";
	        }

	        ZipInputStream zip =
	                new ZipInputStream(input);

	        ZipEntry entry;

	        StringBuilder result =
	                new StringBuilder();

	        int entryCount = 0;

	        while ((entry = zip.getNextEntry()) != null) {

	            entryCount++;

	            result.append(entry.getName())
	                    .append("\n");

	            zip.closeEntry();
	        }

	        zip.close();
	        input.close();

	        return "XLSX TEST\n\n" +
	                "Entries: " +
	                entryCount +
	                "\n\n" +
	                result.toString();

	    } catch (Exception e) {

	        return "XLSX ERROR\n\n" +
	                e.getMessage();
	    }
	}*/

	/*private String readXlsxTest(android.net.Uri uri) {

	    try {

	        InputStream input =
	                getContentResolver()
	                        .openInputStream(uri);

	        if (input == null) {
	            return "Cannot open XLSX file.";
	        }

	        ZipInputStream zip =
	                new ZipInputStream(input);

	        ZipEntry entry;

	        StringBuilder result =
	                new StringBuilder();

	        int entryCount = 0;

	        while ((entry = zip.getNextEntry()) != null) {

	            entryCount++;

	            String name =
	                    entry.getName();

	            if (name.equals("xl/workbook.xml")
	                    || name.startsWith("xl/worksheets/")) {

	                result.append(name)
	                        .append("\n");
	            }

	            zip.closeEntry();
	        }

	        zip.close();
	        input.close();

	        return "XLSX TEST\n\n" +
	                "Entries checked: " +
	                entryCount +
	                "\n\n" +
	                result.toString();

	    } catch (Exception e) {

	        return "XLSX ERROR\n\n" +
	                e.getMessage();
	    }
	}*/

    // ============================================================
    // LOCAL HTTP BRIDGE
    // ============================================================

    private void handleSharedFile(Intent intent) {

        if (intent == null) {
            return;
        }

        if (!Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }

        String text = intent.getStringExtra(Intent.EXTRA_TEXT);

        if (text != null) {

            byte[] textBytes =
                    text.getBytes(StandardCharsets.UTF_8);

            byte[] data =
                    new byte[textBytes.length + 3];

            data[0] = 0x1B; // ESC
            data[1] = 0x40; // @

            System.arraycopy(
                    textBytes,
                    0,
                    data,
                    2,
                    textBytes.length
            );

            data[data.length - 2] = 0x0A; // LF
            data[data.length - 1] = 0x0C; // FF

            int result = printRaw(data);

            boolean ok = result > 0;

            showStatus(
                    ok
                            ? "PRINT SUCCESS\n\nTXT printed."
                            : "PRINT FAILED"
            );

            return;
        }

        android.net.Uri uri =
                intent.getParcelableExtra(Intent.EXTRA_STREAM);

        if (uri != null) {

            String mimeType = intent.getType();

            if (mimeType == null) {
                mimeType =
                        getContentResolver().getType(uri);
            }

            if (mimeType == null) {
                mimeType = "unknown";
            }

	    if (mimeType.equals(
	            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {

	        String testResult =
	                readXlsxTest(uri);

	        showStatus(testResult);

	        return;
	    }

	    if (mimeType.equals("application/pdf")) {

		//printPdfEscpR(uri);
	        pendingPrintUri = uri;
	        showPrintSettings();
	        return;
	    }

	    showStatus(
	            "RECEIVED FILE\n\n" +
	            "MIME: " + mimeType
	    );

            // TXT file
            if (mimeType.equals("text/plain")
                    || mimeType.startsWith("text/")) {

                try {

                    InputStream input =
                            getContentResolver()
                                    .openInputStream(uri);
 
                    if (input == null) {
                        showStatus(
                                "FAILED\n\n" +
                                "Cannot open file."
                        );
                        return;
                    }

                    ByteArrayOutputStream output =
                            new ByteArrayOutputStream();

                    byte[] buffer = new byte[1024];

                    int length;

                    while ((length =
                            input.read(buffer)) != -1) {

                        output.write(
                                buffer,
                                0,
                                length
                        );
                    }

                    input.close();

                    byte[] textBytes =
                            output.toByteArray();

                    byte[] data =
                            new byte[textBytes.length + 3];

                    data[0] = 0x1B; // ESC
                    data[1] = 0x40; // @

                    System.arraycopy(
                            textBytes,
                            0,
                            data,
                            2,
                            textBytes.length
                    );

                    data[data.length - 2] = 0x0A; // LF
                    data[data.length - 1] = 0x0C; // FF

                    int result = printRaw(data);

                    boolean ok = result > 0;

                    showStatus(
                            ok
                                    ? "PRINT SUCCESS\n\nTXT file printed."
                                    : "PRINT FAILED"
                    );

                } catch (Exception e) {

                    showStatus(
                            "FILE ERROR\n\n" +
                            e.getMessage()
                    );
                }

                return;
            }

            // Other file types
	    // Image decoding test
	    if (mimeType.startsWith("image/")) {

	        try {

	            InputStream input =
	                    getContentResolver()
	                            .openInputStream(uri);

	            if (input == null) {

	                showStatus(
	                        "IMAGE ERROR\n\n" +
	                        "Cannot open image."
	                );

	                return;
	            }

	            Bitmap bitmap =
	                    BitmapFactory.decodeStream(input);

	            input.close();

	            if (bitmap == null) {

	                showStatus(
	                        "IMAGE ERROR\n\n" +
	                        "Android could not decode this image."
	                );

	                return;
	            }

	            int width = bitmap.getWidth();
	            int height = bitmap.getHeight();

	            // Limit image width for Epson 80mm printer
	            int maxWidth = 576;

	            if (width > maxWidth) {

	                int newHeight =
	                        (height * maxWidth) / width;

	                bitmap = Bitmap.createScaledBitmap(
	                        bitmap,
	                        maxWidth,
	                        newHeight,
	                        true
	                );

	                width = bitmap.getWidth();
	                height = bitmap.getHeight();
	            }

	            // Convert bitmap to 1-bit black/white raster
                    int bytesPerRow = (width + 7) / 8;

	            byte[] raster =
	                    new byte[bytesPerRow * height];

	            for (int y = 0; y < height; y++) {

	                for (int x = 0; x < width; x++) {

	                    int pixel =
	                            bitmap.getPixel(x, y);

	                    int red =
	                            (pixel >> 16) & 0xFF;

	                    int green =
	                            (pixel >> 8) & 0xFF;

	                    int blue =
	                            pixel & 0xFF;

	                    int gray =
	                            (red * 299 +
	                             green * 587 +
	                             blue * 114) / 1000;

	                    if (gray < 128) {

	                        int index =
	                                y * bytesPerRow +
	                                (x / 8);

	                        raster[index] |=
	                                (byte) (0x80 >> (x % 8));
	                    }
	                }
	            }

		    int rasterSize = raster.length;

		    ByteArrayOutputStream printData =
		            new ByteArrayOutputStream();

		    // Initialize printer
		    printData.write(0x1B);
		    printData.write(0x40);

		    // Enter ESC/P graphics mode
		    printData.write(0x1B);
		    printData.write(0x28);
		    printData.write(0x47);
		    printData.write(0x01);
		    printData.write(0x00);
		    printData.write(0x01);

		    // 24-dot vertical bands
		    printData.write(0x1B);
		    printData.write(0x2B);
		    printData.write(24);

		    int imageWidth = width;
		    int imageHeight = height;

		    int bandHeight = 24;

		    for (int y = 0; y < imageHeight; y += bandHeight) {

		        int remaining =
		                imageHeight - y;

		        int rowsThisBand =
		                Math.min(bandHeight, remaining);

		        // ESC . raster graphics
		        printData.write(0x1B);
		        printData.write(0x2E);

		        // Full graphics mode
		        printData.write(0);

		        // Vertical resolution
		        printData.write(10);

		        // Horizontal resolution
		        printData.write(10);

		        // Always send a 24-row band
		        printData.write(24);

		        // Width
		        printData.write(
		                imageWidth & 0xFF
		        );

		        printData.write(
		                (imageWidth >> 8) & 0xFF
		        );

		        // Send image rows
		        for (int row = 0;
		             row < bandHeight;
		             row++) {

		            int sourceY = y + row;

		            if (sourceY < imageHeight) {

		                int rowStart =
		                        sourceY * bytesPerRow;

		                for (int x = 0;
		                     x < bytesPerRow;
		                     x++) {

		                    printData.write(
		                            raster[rowStart + x]
		                                    & 0xFF
		                    );
		                }

		            } else {

		                // Pad the last band with white
		                for (int x = 0;
		                     x < bytesPerRow;
		                     x++) {

		                    printData.write(0x00);
		                }
		            }
		        }

		        // Next raster band
		        printData.write(0x0D);
		        printData.write(0x0A);
		    }

		    // Finish page
		    printData.write(0x0C);

		    // Reset printer
		    printData.write(0x1B);
		    printData.write(0x40);

		    byte[] imageData =
		            printData.toByteArray();

		    int printResult =
		            printRaw(imageData);

		    boolean printOk =
		            printResult > 0;

		    bitmap.recycle();

		    showStatus(
		            printOk
		                    ? "IMAGE PRINT SUCCESS\n\n" +
		                      "Image sent to Epson."
		                    : "IMAGE PRINT FAILED"
		    );

		    bitmap.recycle();

		    showStatus(
		            printOk
		                    ? "IMAGE TEST PRINT SUCCESS\n\n" +
		                      "Raster test sent."
		                    : "IMAGE TEST PRINT FAILED"
		    );

	        } catch (Exception e) {

	            showStatus(
	                    "IMAGE ERROR\n\n" +
	                    e.getMessage()
	            );
	        }

	        return;
	}

	// Other file types
	showStatus(
	        "FILE RECEIVED\n\n" +
	        "Type: " + mimeType +
	        "\n\n" +
	        "This file needs document/image processing."
	);

	return;
	        
        }

        showStatus(
                "SHARE RECEIVED\n\n" +
                "No text or file data found."
        );
    }

    private void startBridgeServer() {

        serverThread = new Thread(() -> {

            try {

                serverSocket =
                        new ServerSocket(
                                BRIDGE_PORT,
                                10,
                                java.net.InetAddress.getByName(
                                        "127.0.0.1"
                                )
                        );

                showStatus(
                        "CSR Print Bridge READY\n\n" +
                        "Bridge:\n" +
                        "http://127.0.0.1:" +
                        BRIDGE_PORT
                );

                while (!serverSocket.isClosed()) {

                    Socket client =
                            serverSocket.accept();

                    new Thread(() ->
                            handleClient(client)
                    ).start();
                }

            } catch (Exception e) {

                showStatus(
                        "Bridge server error:\n\n" +
                        e.getMessage()
                );
            }

        });

        serverThread.start();
    }

    private void handleClient(Socket socket) {

        try {

            InputStream input =
                    socket.getInputStream();

            OutputStream output =
                    socket.getOutputStream();

            BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    input,
                                    StandardCharsets.ISO_8859_1
                            )
                    );

            String requestLine =
                    reader.readLine();

            if (requestLine == null) {
                socket.close();
                return;
            }

            String[] requestParts =
                    requestLine.split(" ");

            if (requestParts.length < 2) {
                socket.close();
                return;
            }

            String method = requestParts[0];
            String path = requestParts[1];

            int contentLength = 0;

            String line;

            while ((line = reader.readLine()) != null) {

                if (line.isEmpty()) {
                    break;
                }

                String lower =
                        line.toLowerCase();

                if (lower.startsWith("content-length:")) {

                    String value =
                            line.substring(
                                    line.indexOf(":") + 1
                            ).trim();

                    try {
                        contentLength =
                                Integer.parseInt(value);
                    } catch (Exception ignored) {
                    }
                }
            }

            // ----------------------------------------------------
            // CORS preflight
            // ----------------------------------------------------

            if ("OPTIONS".equalsIgnoreCase(method)) {

                sendResponse(
                        output,
                        200,
                        "OK",
                        "text/plain",
                        "OK".getBytes(
                                StandardCharsets.UTF_8
                        )
                );

                socket.close();
                return;
            }

            // ----------------------------------------------------
            // STATUS
            // ----------------------------------------------------

            if ("GET".equalsIgnoreCase(method)
                    && "/status".equals(path)) {

                String response =
                        "{\"status\":\"ok\",\"bridge\":\"CSR Print Bridge\"}";

                sendResponse(
                        output,
                        200,
                        "OK",
                        "application/json",
                        response.getBytes(
                                StandardCharsets.UTF_8
                        )
                );

                socket.close();
                return;
            }

            // ----------------------------------------------------
            // PRINT
            // ----------------------------------------------------

            if ("POST".equalsIgnoreCase(method)
                    && "/print".equals(path)) {

                if (contentLength <= 0) {

                    sendResponse(
                            output,
                            400,
                            "Bad Request",
                            "text/plain",
                            "No print data"
                                    .getBytes(
                                            StandardCharsets.UTF_8
                                    )
                    );

                    socket.close();
                    return;
                }

                ByteArrayOutputStream data =
                        new ByteArrayOutputStream();

                byte[] buffer = new byte[4096];

                int remaining =
                        contentLength;

                while (remaining > 0) {

                    int read =
                            input.read(
                                    buffer,
                                    0,
                                    Math.min(
                                            buffer.length,
                                            remaining
                                    )
                            );

                    if (read == -1) {
                        break;
                    }

                    data.write(
                            buffer,
                            0,
                            read
                    );

                    remaining -= read;
                }

                byte[] printData =
                        data.toByteArray();

                int result =
                        printRaw(printData);

                if (result >= 0) {

                    String response =
                            "{\"status\":\"printed\",\"bytes\":"
                                    + result
                                    + "}";

                    sendResponse(
                            output,
                            200,
                            "OK",
                            "application/json",
                            response.getBytes(
                                    StandardCharsets.UTF_8
                            )
                    );

                    showStatus(
                            "PRINT RECEIVED\n\n" +
                            "Bytes: " +
                            printData.length +
                            "\n\n" +
                            "USB sent: " +
                            result
                    );

                } else {

                    sendResponse(
                            output,
                            500,
                            "Printer Error",
                            "text/plain",
                            "USB print failed"
                                    .getBytes(
                                            StandardCharsets.UTF_8
                                    )
                    );
                }

                socket.close();
                return;
            }

            sendResponse(
                    output,
                    404,
                    "Not Found",
                    "text/plain",
                    "Not Found".getBytes(
                            StandardCharsets.UTF_8
                    )
            );

            socket.close();

        } catch (Exception e) {

            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void sendResponse(
            OutputStream output,
            int code,
            String message,
            String contentType,
            byte[] body
    ) throws Exception {

        String headers =
                "HTTP/1.1 "
                        + code
                        + " "
                        + message
                        + "\r\n"
                        + "Content-Type: "
                        + contentType
                        + "\r\n"
                        + "Content-Length: "
                        + body.length
                        + "\r\n"
                        + "Access-Control-Allow-Origin: *\r\n"
                        + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                        + "Access-Control-Allow-Headers: Content-Type\r\n"
                        + "Access-Control-Allow-Private-Network: true\r\n"
                        + "Connection: close\r\n"
                        + "\r\n";

        output.write(
                headers.getBytes(
                        StandardCharsets.ISO_8859_1
                )
        );

        output.write(body);
        output.flush();
    }

    // ============================================================
    // USB PRINTER
    // ============================================================

    private int printRaw(byte[] data) {

        UsbDevice device = null;

        for (UsbDevice d :
                usbManager.getDeviceList().values()) {

            if (d.getVendorId() == EPSON_VID
                    && d.getProductId() == EPSON_PID) {

                device = d;
                break;
            }
        }

        if (device == null) {
            return -1;
        }

        UsbInterface printerInterface = null;
        UsbEndpoint outEndpoint = null;

        for (int i = 0;
             i < device.getInterfaceCount();
             i++) {

            UsbInterface usbInterface =
                    device.getInterface(i);

            if (usbInterface.getId() == 1) {

                printerInterface =
                        usbInterface;

                for (int j = 0;
                     j < usbInterface.getEndpointCount();
                     j++) {

                    UsbEndpoint endpoint =
                            usbInterface.getEndpoint(j);

                    if (endpoint.getDirection()
                            == UsbConstants.USB_DIR_OUT
                            &&
                            endpoint.getType()
                            == UsbConstants.USB_ENDPOINT_XFER_BULK) {

                        outEndpoint = endpoint;
                        break;
                    }
                }

                break;
            }
        }

        if (printerInterface == null
                || outEndpoint == null) {

            return -1;
        }

        if (!usbManager.hasPermission(device)) {
            return -1;
        }

        UsbDeviceConnection connection =
                usbManager.openDevice(device);

        if (connection == null) {
            return -1;
        }

        boolean claimed =
                connection.claimInterface(
                        printerInterface,
                        true
                );

        if (!claimed) {

            connection.close();
            return -1;
        }

        try {

            return connection.bulkTransfer(
                    outEndpoint,
                    data,
                    data.length,
                    10000
            );

        } finally {

            connection.releaseInterface(
                    printerInterface
            );

            connection.close();
        }
    }

    // ============================================================
    // USB DEVICE / PERMISSION
    // ============================================================

    private void findPrinter() {

        UsbDevice printer = null;

        for (UsbDevice device :
                usbManager.getDeviceList().values()) {

            if (device.getVendorId() == EPSON_VID
                    && device.getProductId() == EPSON_PID) {

                printer = device;
                break;
            }
        }

        if (printer == null) {

            showStatus(
                    "CSR Print Bridge\n\n" +
                    "Epson L3210 not found.\n\n" +
                    "Connect the printer through OTG."
            );

            return;
        }

        if (usbManager.hasPermission(printer)) {

            showStatus(
                    "CSR Print Bridge READY\n\n" +
                    "Epson L3210 connected.\n\n" +
                    "Bridge:\n" +
                    "http://127.0.0.1:" +
                    BRIDGE_PORT
            );

            return;
        }

        showStatus(
                "Epson L3210 found.\n\n" +
                "Requesting USB permission..."
        );

        Intent permissionIntent =
                new Intent(
                        ACTION_USB_PERMISSION
                );

        permissionIntent.setPackage(
                getPackageName()
        );

        android.app.PendingIntent pendingIntent =
                android.app.PendingIntent.getBroadcast(
                        this,
                        0,
                        permissionIntent,
                        android.app.PendingIntent.FLAG_IMMUTABLE
                );

        usbManager.requestPermission(
                printer,
                pendingIntent
        );
    }

    private void showStatus(String message) {

        runOnUiThread(() ->
                statusText.setText(
                        "CSR Print Bridge\n\n" +
                        message
                )
        );
    }

    @Override
    protected void onDestroy() {

        try {
            unregisterReceiver(
                    usbReceiver
            );
        } catch (Exception ignored) {
        }

        try {

            if (serverSocket != null) {
                serverSocket.close();
            }

        } catch (Exception ignored) {
        }

        super.onDestroy();
    }

    private void testEscprPrint() {

        try {

            ByteArrayOutputStream out =
                    new ByteArrayOutputStream();

            // ESC/P-R mode
            out.write(0x1B);
            out.write('(');
            out.write('R');
            out.write(0x06);
            out.write(0x00);
            out.write(0x00);
            out.write('E');
            out.write('S');
            out.write('C');
            out.write('P');
            out.write('R');

            // setq
            byte[] setqData = new byte[] {
                    0,      // media type: plain
                    4,      // quality
                    0,      // color mode
                    0,      // brightness
                    0,      // contrast
                    0,      // saturation
                    1,      // color plane
                    0, 0    // palette length
            };

            out.write(0x1B);
            out.write('q');

            int setqLength = setqData.length;
            out.write(setqLength & 0xFF);
            out.write((setqLength >> 8) & 0xFF);
            out.write((setqLength >> 16) & 0xFF);
            out.write((setqLength >> 24) & 0xFF);

            out.write('s');
            out.write('e');
            out.write('t');
            out.write('q');

            out.write(setqData);

            // A4 at 360 dpi
            int dpi = 360;

            int paperWidth =
                    (int)Math.ceil(210.0 / 25.4 * dpi);

            int paperHeight =
                    (int)Math.ceil(297.0 / 25.4 * dpi);

            int printableWidth = paperWidth;
            int printableHeight = paperHeight;

            // setj
            ByteArrayOutputStream setj =
                    new ByteArrayOutputStream();

            writeUInt32BE(setj, paperWidth);
            writeUInt32BE(setj, paperHeight);

            // top margin
            writeUInt16BE(setj, 0);

            // left margin
            writeUInt16BE(setj, 0);

            writeUInt32BE(setj, printableWidth);
            writeUInt32BE(setj, printableHeight);

            // 360 dpi resolution mode
            setj.write(0);

            // bidirectional
            setj.write(0);

            byte[] setjData = setj.toByteArray();

            out.write(0x1B);
            out.write('j');

            int setjLength = setjData.length;

            out.write(setjLength & 0xFF);
            out.write((setjLength >> 8) & 0xFF);
            out.write((setjLength >> 16) & 0xFF);
            out.write((setjLength >> 24) & 0xFF);

            out.write('s');
            out.write('e');
            out.write('t');
            out.write('j');

            out.write(setjData);

            // Start page
            out.write(0x1B);
            out.write('p');
            out.write(0x00);
            out.write(0x00);
            out.write(0x00);
            out.write(0x00);
            out.write('s');
            out.write('t');
            out.write('t');
            out.write('p');

            // Simple black test line
            int testWidth = 500;
            byte[] line =
                    new byte[(testWidth + 7) / 8];

            for (int i = 0; i < line.length; i++) {
                line[i] = (byte)0xFF;
            }

            // dsnd
            ByteArrayOutputStream dsnd =
                    new ByteArrayOutputStream();

            writeUInt16BE(dsnd, 0); // x
            writeUInt16BE(dsnd, 0); // y
            dsnd.write(0);           // no compression
            writeUInt16BE(dsnd, line.length);

            dsnd.write(line);

            byte[] dsndData = dsnd.toByteArray();

            out.write(0x1B);
            out.write('d');

            int dsndLength = dsndData.length;

            out.write(dsndLength & 0xFF);
            out.write((dsndLength >> 8) & 0xFF);
            out.write((dsndLength >> 16) & 0xFF);
            out.write((dsndLength >> 24) & 0xFF);

            out.write('d');
            out.write('s');
            out.write('n');
            out.write('d');

            out.write(dsndData);

            // End page
            out.write(0x1B);
            out.write('p');
            out.write(0x01);
            out.write(0x00);
            out.write(0x00);
            out.write(0x00);
            out.write('e');
            out.write('n');
            out.write('d');
            out.write('p');
            out.write(0);

            // End job
            out.write(0x1B);
            out.write('j');
            out.write(0x00);
            out.write(0x00);
            out.write(0x00);
            out.write(0x00);
            out.write('e');
            out.write('n');
            out.write('d');
            out.write('j');

            int result =
                    printRaw(out.toByteArray());

            if (result > 0) {

                showStatus(
                        "ESC/P-R TEST SENT\n\n" +
                        "A4 360 DPI"
                );

            } else {

                showStatus(
                        "ESC/P-R TEST FAILED"
                );
            }

        } catch (Exception e) {

            showStatus(
                    "ESC/P-R ERROR\n\n" +
                    e.getMessage()
            );
        }
    }

    private void writeUInt32BE(
            ByteArrayOutputStream out,
            int value) {

        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private void writeUInt16BE(
            ByteArrayOutputStream out,
            int value) {

        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }


}
