/*
 Copyright (c) 2012 Roman Truba

 Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 permit persons to whom the Software is furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in all copies or substantial
 portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
 THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH
 THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package ru.truba.touchgallery;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.io.*;
import java.util.ArrayList;

public class GalleryFileActivity extends Activity {

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        ArrayList<String> items = new ArrayList<String>();
		try {
            String[] urls = getAssets().list("");
	
	        for (String filename : urls) 
	        {
	        	if (filename.matches(".+\\.(jpg|png)"))
	        	{
	        		String path = getFilesDir() + "/" + filename;
	        		copy(getAssets().open(filename), new File(path) );
	        		items.add(Uri.fromFile(new File(path)).toString());
	        	}
	        }
		} catch (IOException e) {
			e.printStackTrace();
		}

        startActivity(new Intent(this, TouchGalleryActivity.class)
                .putStringArrayListExtra(TouchGalleryActivity.EXTRA_URLS, items)
                .putExtra(TouchGalleryActivity.EXTRA_POSITION, 0));
    }

    public void copy(InputStream in, File dst) throws IOException {

        OutputStream out = new FileOutputStream(dst);

        // Transfer bytes from in to out
        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }
}