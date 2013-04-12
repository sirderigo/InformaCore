package org.witness.informacam.informa;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.witness.informacam.InformaCam;
import org.witness.informacam.informa.suckers.AccelerometerSucker;
import org.witness.informacam.informa.suckers.GeoSucker;
import org.witness.informacam.informa.suckers.PhoneSucker;
import org.witness.informacam.models.ISuckerCache;
import org.witness.informacam.models.media.IMedia;
import org.witness.informacam.models.media.IRegion;
import org.witness.informacam.utils.Constants.Actions;
import org.witness.informacam.utils.Constants.App;
import org.witness.informacam.utils.Constants.Codes;
import org.witness.informacam.utils.Constants.IManifest;
import org.witness.informacam.utils.Constants.SuckerCacheListener;
import org.witness.informacam.utils.Constants.Suckers;
import org.witness.informacam.utils.Constants.Suckers.CaptureEvent;
import org.witness.informacam.utils.Constants.Suckers.Phone;
import org.witness.informacam.utils.LogPack;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

public class InformaService extends Service implements SuckerCacheListener {
	private final IBinder binder = new LocalBinder();
	private static InformaService informaService;

	ExecutorService ex;
	
	private long startTime = 0L;
	private long realStartTime = 0L;

	private int GPS_WAITING = 0;

	public SensorLogger<GeoSucker> _geo;
	public SensorLogger<PhoneSucker> _phone;
	public SensorLogger<AccelerometerSucker> _acc;

	private LoadingCache<Long, LogPack> cache;
	InformaCam informaCam;

	Handler h = new Handler();
	IMedia associatedMedia = null;
	
	private InformaBroadcaster[] broadcasters = {
			new InformaBroadcaster(new IntentFilter(BluetoothDevice.ACTION_FOUND)),
			new InformaBroadcaster(new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
	};

	private final static String LOG = App.Informa.LOG;

	public class LocalBinder extends Binder {
		public InformaService getService() {
			return InformaService.this;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return binder;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onCreate() {
		Log.d(LOG, "started.");
		informaCam = InformaCam.getInstance();
		
		for(BroadcastReceiver broadcaster : broadcasters) {
			this.registerReceiver(broadcaster, ((InformaBroadcaster) broadcaster).intentFilter);
		}

		initCache();

		_geo = new GeoSucker();
		_phone = new PhoneSucker();
		_acc = new AccelerometerSucker();

		informaService = InformaService.this;
		sendBroadcast(new Intent().putExtra(Codes.Keys.SERVICE, Codes.Routes.INFORMA_SERVICE).setAction(Actions.ASSOCIATE_SERVICE));

		init();
	}

	public long getCurrentTime() {
		try {
			return ((GeoSucker) _geo).getTime();
		} catch(NullPointerException e) {
			return 0;
		}
	}

	public void associateMedia(IMedia media) {
		this.associatedMedia = media;
	}

	private void init() {
		h.post(new Runnable() {
			@Override
			public void run() {
				long currentTime = getCurrentTime();
				Log.d(LOG, "time: " + currentTime);
				if(currentTime == 0) {
					GPS_WAITING++;

					if(GPS_WAITING < Suckers.GPS_WAIT_MAX) {
						h.postDelayed(this, 200);
					} else {
						Toast.makeText(InformaService.this, "NO GPS!", Toast.LENGTH_LONG).show();
						stopSelf();
					}
					return;
				}

				realStartTime = currentTime;
				onUpdate(((GeoSucker) _geo).forceReturn());
				try {
					onUpdate(((PhoneSucker) _phone).forceReturn());
				} catch (JSONException e) {
					Log.e(LOG, e.toString());
					e.printStackTrace();
				}
				sendBroadcast(new Intent().setAction(Actions.INFORMA_START));
			}
		});

	}

	private void initCache() {
		startTime = System.currentTimeMillis();
		cache = CacheBuilder.newBuilder()
				.build(new CacheLoader<Long, LogPack>() {

					@Override
					public LogPack load(Long timestamp) throws Exception {
						return cache.getUnchecked(timestamp);
					}

				});
	}

	private void saveCache() {
		info.guardianproject.iocipher.File cacheRoot = new info.guardianproject.iocipher.File(IManifest.CACHES);
		if(!cacheRoot.exists()) {
			cacheRoot.mkdir();
		}

		info.guardianproject.iocipher.File cacheFile = new info.guardianproject.iocipher.File(cacheRoot, startTime + "_" + System.currentTimeMillis());

		ISuckerCache suckerCache = new ISuckerCache();
		JSONArray cacheArray = new JSONArray();
		Iterator<Entry<Long, LogPack>> cIt = cache.asMap().entrySet().iterator();
		while(cIt.hasNext()) {
			JSONObject cacheMap = new JSONObject();
			Entry<Long, LogPack> c = cIt.next();
			try {
				cacheMap.put(String.valueOf(c.getKey()), c.getValue());
				cacheArray.put(cacheMap);
			} catch(JSONException e) {
				Log.e(LOG, e.toString());
				e.printStackTrace();
			}
		}
		suckerCache.timeOffset = realStartTime;
		suckerCache.cache = cacheArray;

		Log.d(LOG, suckerCache.asJson().toString());
		informaCam.ioService.saveBlob(suckerCache.asJson().toString().getBytes(), cacheFile);

		if(associatedMedia != null) {
			IMedia media = informaCam.mediaManifest.getById(associatedMedia._id);
			if(media.associatedCaches == null) {
				media.associatedCaches = new ArrayList<String>();
			}
			media.associatedCaches.add(cacheFile.getAbsolutePath());
			informaCam.saveState(informaCam.mediaManifest);
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		saveCache();

		try {
			_geo.getSucker().stopUpdates();
			_phone.getSucker().stopUpdates();
			_acc.getSucker().stopUpdates();
		} catch(NullPointerException e) {
			e.printStackTrace();
		}

		_geo = null;
		_phone = null;
		_acc = null;
		
		for(BroadcastReceiver b : broadcasters) {
			unregisterReceiver(b);
		}

		sendBroadcast(new Intent().setAction(Actions.INFORMACAM_STOP));
		sendBroadcast(new Intent().putExtra(Codes.Keys.SERVICE, Codes.Routes.INFORMA_SERVICE).setAction(Actions.DISASSOCIATE_SERVICE));
	}

	public static InformaService getInstance() {
		return informaService;
	}
	
	public List<LogPack> getAllEventsByType(final int type, final LoadingCache<Long, LogPack> cache) throws InterruptedException, ExecutionException {
		ex = Executors.newFixedThreadPool(100);
		Future<List<LogPack>> query = ex.submit(new Callable<List<LogPack>>() {

			@Override
			public List<LogPack> call() throws Exception {
				Iterator<Entry<Long, LogPack>> cIt = cache.asMap().entrySet().iterator();
				List<LogPack> events = new ArrayList<LogPack>();
				while(cIt.hasNext()) {
					Entry<Long, LogPack> entry = cIt.next();
					if(entry.getValue().has(CaptureEvent.Keys.TYPE) && entry.getValue().getInt(CaptureEvent.Keys.TYPE) == type)
						events.add(entry.getValue());
				}

				return events;
			}
		});

		List<LogPack> events = query.get();
		ex.shutdown();

		return events;
	}

	public List<Entry<Long, LogPack>> getAllEventsByTypeWithTimestamp(final int type, final LoadingCache<Long, LogPack> cache) throws JSONException, InterruptedException, ExecutionException {
		ex = Executors.newFixedThreadPool(100);
		Future<List<Entry<Long, LogPack>>> query = ex.submit(new Callable<List<Entry<Long, LogPack>>>() {

			@Override
			public List<Entry<Long, LogPack>> call() throws Exception {
				Iterator<Entry<Long, LogPack>> cIt = cache.asMap().entrySet().iterator();
				List<Entry<Long, LogPack>> events = new ArrayList<Entry<Long, LogPack>>();
				while(cIt.hasNext()) {
					Entry<Long, LogPack> entry = cIt.next();
					if(entry.getValue().has(CaptureEvent.Keys.TYPE) && entry.getValue().getInt(CaptureEvent.Keys.TYPE) == type)
						events.add(entry);
				}

				return events;
			}
		});

		List<Entry<Long, LogPack>> events = query.get();
		ex.shutdown();

		return events;
	}

	public Entry<Long, LogPack> getEventByTypeWithTimestamp(final int type, final LoadingCache<Long, LogPack> cache) throws JSONException, InterruptedException, ExecutionException {
		ex = Executors.newFixedThreadPool(100);
		Future<Entry<Long, LogPack>> query = ex.submit(new Callable<Entry<Long, LogPack>>() {

			@Override
			public Entry<Long, LogPack> call() throws Exception {
				Iterator<Entry<Long, LogPack>> cIt = cache.asMap().entrySet().iterator();
				Entry<Long, LogPack> entry = null;
				while(cIt.hasNext() && entry == null) {
					Entry<Long, LogPack> e = cIt.next();
					if(e.getValue().has(CaptureEvent.Keys.TYPE) && e.getValue().getInt(CaptureEvent.Keys.TYPE) == type)
						entry = e;
				}

				return entry;
			}
		});

		Entry<Long, LogPack> entry = query.get();
		ex.shutdown();

		return entry;
	}

	public LogPack getEventByType(final int type, final LoadingCache<Long, LogPack> cache) throws JSONException, InterruptedException, ExecutionException {
		ex = Executors.newFixedThreadPool(100);
		Future<LogPack> query = ex.submit(new Callable<LogPack>() {

			@Override
			public LogPack call() throws Exception {
				Iterator<LogPack> cIt = cache.asMap().values().iterator();
				LogPack logPack = null;
				while(cIt.hasNext() && logPack == null) {
					LogPack lp = cIt.next();

					if(lp.has(CaptureEvent.Keys.TYPE) && lp.getInt(CaptureEvent.Keys.TYPE) == type)
						logPack = lp;
				}

				return logPack;
			}

		});
		LogPack logPack = query.get();
		ex.shutdown();

		return logPack;
	}
	
	@SuppressWarnings("unchecked")
	public boolean removeRegion(IRegion region) {
		try { 
			LogPack logPack = cache.getIfPresent(region.timestamp);
			if(logPack.has(CaptureEvent.Keys.TYPE) && logPack.getInt(CaptureEvent.Keys.TYPE) == CaptureEvent.REGION_GENERATED) {
				logPack.remove(CaptureEvent.Keys.TYPE);
			}
			
			Iterator<String> repIt = region.asJson().keys();
			while(repIt.hasNext()) {
				logPack.remove(repIt.next());
			}
			
			return true;
		} catch(NullPointerException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		} catch (JSONException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		}
		
		return false;
	}

	@SuppressWarnings("unchecked")
	public void addRegion(IRegion region) {
		LogPack logPack = new LogPack(CaptureEvent.Keys.TYPE, CaptureEvent.REGION_GENERATED);
		Iterator<String> rIt = region.asJson().keys();
		while(rIt.hasNext()) {
			String key = rIt.next();
			try {
				logPack.put(key, region.asJson().get(key));
			} catch (JSONException e) {
				Log.e(LOG, e.toString());
				e.printStackTrace();
			}
		}
		
		region.timestamp = onUpdate(logPack);
	}
	
	@SuppressWarnings("unchecked")
	public void updateRegion(IRegion region) {
		try {
			LogPack logPack = cache.getIfPresent(region.timestamp);
			Iterator<String> repIt = region.asJson().keys();
			while(repIt.hasNext()) {
				String key = repIt.next();
				logPack.put(key, region.asJson().get(key));
			}
		} catch(JSONException e) {
			Log.e(LOG, e.toString());
			e.printStackTrace();
		}

	}

	@SuppressWarnings({ "unchecked", "unused" })
	private LogPack JSONObjectToLogPack(JSONObject json) throws JSONException {
		LogPack logPack = new LogPack();
		Iterator<String> jIt = json.keys();
		while(jIt.hasNext()) {
			String key = jIt.next();
			logPack.put(key, json.get(key));
		}
		return logPack;
	}

	@SuppressWarnings("unused")
	private void pushToSucker(SensorLogger<?> sucker, LogPack logPack) throws JSONException {
		if(sucker.getClass().equals(PhoneSucker.class))
			_phone.sendToBuffer(logPack);
	}

	@SuppressWarnings("unchecked")
	@Override
	public void onUpdate(long timestamp, LogPack logPack) {
		try {
			LogPack lp = cache.getIfPresent(timestamp);
			if(lp != null) {
				Iterator<String> lIt = lp.keys();
				while(lIt.hasNext()) {
					String key = lIt.next();
					logPack.put(key, lp.get(key));	
				}
			}

			cache.put(timestamp, logPack);
		} catch(JSONException e) {}
	}

	@Override
	public long onUpdate(LogPack logPack) {
		long timestamp = ((GeoSucker) _geo).getTime();
		onUpdate(timestamp, logPack);
		return timestamp;
	}

	class InformaBroadcaster extends BroadcastReceiver {
		IntentFilter intentFilter;
		
		public InformaBroadcaster(IntentFilter intentFilter) {
			this.intentFilter = intentFilter;
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			if(intent.getAction().equals(BluetoothDevice.ACTION_FOUND)) {
				try {
					BluetoothDevice bd = (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
					LogPack logPack = new LogPack(Phone.Keys.BLUETOOTH_DEVICE_ADDRESS, bd.getAddress());
					logPack.put(Phone.Keys.BLUETOOTH_DEVICE_NAME, bd.getName());
					onUpdate(logPack);

				} catch(JSONException e) {}

			} else if(intent.getAction().equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
				LogPack logPack = new LogPack(Phone.Keys.VISIBLE_WIFI_NETWORKS, ((PhoneSucker) informaService._phone).getWifiNetworks());
				onUpdate(logPack);

			}

		}

	}
}