package info.guardianproject.informacam.camera;

// inspired by https://github.com/commonsguy/cw-omnibus/tree/master/ContentProvider/Pipe

import info.guardianproject.iocipher.File;
import info.guardianproject.iocipher.FileInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseOutputStream;
import android.util.Log;
import android.webkit.MimeTypeMap;

public class IOCipherContentProvider extends ContentProvider {
	public static final String TAG = "IOCipherContentProvider";
	public static final Uri FILES_URI = Uri
			.parse("content://info.guardianproject.iocipherexample/");
	private MimeTypeMap mimeTypeMap;

	@Override
	public boolean onCreate() {
		mimeTypeMap = MimeTypeMap.getSingleton();
		return true;
	}

	@Override
	public String getType(Uri uri) {
		String fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
		String type = mimeTypeMap.getMimeTypeFromExtension(fileExtension);
		return type;
	}

	@Override
	public ParcelFileDescriptor openFile(Uri uri, String mode)
			throws FileNotFoundException {
		ParcelFileDescriptor[] pipe = null;
		InputStream in = null;

		String path = uri.getPath();
		
		try {
			File fileShare = new File(path);
			Log.d(TAG,"found file: " + fileShare.getAbsolutePath() + " size=" + fileShare.length());
			pipe = ParcelFileDescriptor.createPipe();			
			in = new FileInputStream(fileShare);
			new PipeFeederThread(in,
					new AutoCloseOutputStream(pipe[1])
				{
				
				}		
					).start();
			
			
		} catch (IOException e) {
			Log.e(TAG, "Error opening pipe", e);
			throw new FileNotFoundException("Could not open pipe for: "
					+ uri.toString());
		}		
		return (pipe[0]);
	}

	@Override
	public Cursor query(Uri url, String[] projection, String selection,
			String[] selectionArgs, String sort) {
		
		Log.d(TAG,"query called: " + url.toString());
		
		throw new RuntimeException("Operation not supported");
	}

	@Override
	public Uri insert(Uri uri, ContentValues initialValues) {
		throw new RuntimeException("Operation not supported");
	}

	@Override
	public int update(Uri uri, ContentValues values, String where,
			String[] whereArgs) {
		throw new RuntimeException("Operation not supported");
	}

	@Override
	public int delete(Uri uri, String where, String[] whereArgs) {
		throw new RuntimeException("Operation not supported");
	}

	static class PipeFeederThread extends Thread {
		InputStream in;
		BufferedOutputStream out;

		PipeFeederThread(InputStream in, OutputStream out) {
			this.in = in;
			this.out = new BufferedOutputStream(out, 32000);	
			setDaemon(true);
		}

		@Override
		public void run() {
			
			byte[] buf = new byte[4096];
			int len;

			try {
				
				int idx = 0;
				
				while ((len = in.read(buf)) != -1)
				{
					out.write(buf, 0, len);
					idx+=buf.length;
					Log.d(TAG,"writing video at " + idx);
				}
				
				in.close();
				out.flush();
				out.close();
				
			} catch (IOException e) {
				Log.e(TAG, "File transfer failed:", e);
			}
		}
	}
}
