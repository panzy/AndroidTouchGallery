package ru.truba.touchgallery;

import android.app.ListActivity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SimpleAdapter;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends ListActivity {
	
	public static final String TITLE = "title";
	public static final String SUBTITLE = "subtitle";
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
//		setContentView(R.layout.activity_main);
		
		setListAdapter(createAdapter());
	}
	
	protected Map<String, String> createElement(String title, String subtitle) 
	{
		Map<String, String> result = new HashMap<String, String>();
		result.put(TITLE, title);
		result.put(SUBTITLE, subtitle);
		return result;
	}
	public List<Map<String, String>> getData() 
	{
		List<Map<String, String>> result = new ArrayList<Map<String,String>>();
		result.add(createElement("Web load", "In this example, you provide list of URLs to display in gallery"));
		result.add(createElement("Local load", "In this example, you provide list of files  to display in gallery"));
		return result;
	}
	public ListAdapter createAdapter() 
	{
		SimpleAdapter adapter = new SimpleAdapter(this, getData(), 
				android.R.layout.simple_list_item_2, 
				new String[]{TITLE, SUBTITLE}, 
				new int[]{android.R.id.text1, android.R.id.text2});
		return adapter;
	}

	@Override
	protected void onListItemClick(ListView l, View v, int position, long id) {
		Intent i = null;
		switch (position) 
		{
		case 0:
			i = new Intent(this, GalleryUrlActivity.class);
			startActivity(i);
			break;
		case 1:
			startTouchGalleryActivity();
			break;
		}
	}

	private void startTouchGalleryActivity() {
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
