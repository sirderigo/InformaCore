package org.witness.informacam.informa.suckers;

import org.witness.informacam.models.j3m.ILogPack;
import org.witness.informacam.utils.Constants.Suckers;
import org.witness.informacam.utils.Constants.Suckers.Geo;

import android.content.Context;
import android.location.Criteria;
import android.location.Location;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GooglePlayServicesClient.ConnectionCallbacks;
import com.google.android.gms.common.GooglePlayServicesClient.OnConnectionFailedListener;
import com.google.android.gms.location.LocationClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;

public class GeoFusedSucker extends GeoSucker implements ConnectionCallbacks, OnConnectionFailedListener, LocationListener {
	
	Criteria criteria;
	long currentNmeaTime;
	
	private final static String LOG = Suckers.LOG;
	
	private LocationClient mLocationClient;
	private LocationRequest mLocationRequest;
	private Location mLastLocation = null;
	
	private int mLocationPriority = LocationRequest.PRIORITY_HIGH_ACCURACY;
	
	@SuppressWarnings("unchecked")
	public GeoFusedSucker(Context context) {
		super(context);
		setSucker(this);
		
		mLocationClient = new LocationClient(context, this, this);
		mLocationClient.connect();
		
	}
	
	public void setLocationPriority (int newPriority)
	{
		mLocationPriority = newPriority;
	}
	
	public ILogPack forceReturn() {
		
		double[] loc = updateLocation();
		if(loc == null) {
			Log.d(LOG, "location was null");
			loc = new double[] {0d, 0d};
		}
		
		ILogPack iLogPack = new ILogPack(Geo.Keys.GPS_COORDS, "[" + loc[0] + "," + loc[1] + "]");
		
		if (mLastLocation != null){
			try {
				
			if (mLastLocation.hasAccuracy())			
					iLogPack.put(Geo.Keys.GPS_ACCURACY, mLastLocation.getAccuracy()+"");
			
			if (mLastLocation.hasAltitude())			
				iLogPack.put(Geo.Keys.GPS_ALTITUDE, mLastLocation.getAltitude()+"");
		
			if (mLastLocation.hasSpeed())			
				iLogPack.put(Geo.Keys.GPS_SPEED, mLastLocation.getSpeed()+"");
		
			if (mLastLocation.hasBearing())			
				iLogPack.put(Geo.Keys.GPS_BEARING, mLastLocation.getBearing()+"");	
			
			} catch (Exception e) {
				Log.d(LOG,"json exception in location data",e);
			}
			
		}
		
		return iLogPack;
	}
	
	public long getTime() {
		if (mLastLocation != null)
			return mLastLocation.getTime();
		else
			return 0;
	}
	
	public double[] updateLocation() {
		
		if (mLastLocation != null) {
			return new double[] {mLastLocation.getLatitude(),mLastLocation.getLongitude()};
		} else {
			
			if (mLocationClient.isConnected())
			{				
				mLastLocation = mLocationClient.getLastLocation();
			
				if (mLastLocation != null)
					return new double[] {mLastLocation.getLatitude(),mLastLocation.getLongitude()};
				
			}
		}
		
		//nothing here right now
		return null;
	
	}
	
	public void stopUpdates() {
		
		if (mLocationClient != null && mLocationClient.isConnected())
			mLocationClient.disconnect();
	}

	@Override
	public void onConnectionFailed(ConnectionResult arg0) {
		Log.w(LOG,"location connect failed: " + arg0.getErrorCode() + "=" + arg0.toString());
		
	}

	@Override
	public void onConnected(Bundle arg0) {

		mLocationRequest = LocationRequest.create();

		mLocationRequest.setInterval(Geo.LOG_RATE);
		mLocationRequest.setPriority(mLocationPriority);

		mLocationClient.requestLocationUpdates(mLocationRequest, this);
		
	}

	@Override
	public void onDisconnected() {
		
		stopUpdates ();
		
	}

	@Override
	public void onLocationChanged(Location location) {
		mLastLocation = location;
		
		if(mLastLocation != null)
		{
			ILogPack iLogPack = forceReturn();
			
			if (iLogPack != null)
				sendToBuffer(iLogPack);	
		}
	}
}
