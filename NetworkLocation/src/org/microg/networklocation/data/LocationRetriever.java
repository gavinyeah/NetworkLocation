package org.microg.networklocation.data;

import android.util.Log;
import org.microg.networklocation.MainService;
import org.microg.networklocation.database.LocationDatabase;
import org.microg.networklocation.source.LocationSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

public class LocationRetriever {
	private static final String TAG = "nlp.LocationRetriever";
	private static final long WAIT_BETWEEN = 1000 * 10; //every 10 seconds
	private final Thread loopThread = new Thread(new Runnable() {
		@Override
		public void run() {
			retrieveLoop();
		}
	});
	private LocationDatabase locationDatabase;
	private List<LocationSource<CellSpec>> cellLocationSources = new ArrayList<LocationSource<CellSpec>>();
	private List<LocationSource<WifiSpec>> wifiLocationSources = new ArrayList<LocationSource<WifiSpec>>();
	private Deque<CellSpec> cellStack = new LinkedBlockingDeque<CellSpec>();
	private Deque<WifiSpec> wifiStack = new LinkedBlockingDeque<WifiSpec>();
	private long lastRetrieve = 0;


	public LocationRetriever(LocationDatabase locationDatabase) {
		this.locationDatabase = locationDatabase;
	}

	public List<LocationSource<CellSpec>> getCellLocationSources() {
		return cellLocationSources;
	}

	public void setCellLocationSources(List<LocationSource<CellSpec>> cellLocationSources) {
		this.cellLocationSources = new ArrayList<LocationSource<CellSpec>>(cellLocationSources);
	}

	public List<LocationSource<WifiSpec>> getWifiLocationSources() {
		return wifiLocationSources;
	}

	public void setWifiLocationSources(List<LocationSource<WifiSpec>> wifiLocationSources) {
		this.wifiLocationSources = new ArrayList<LocationSource<WifiSpec>>(wifiLocationSources);
	}

	public void queueLocationRetrieval(CellSpec cellSpec) {
		if (!cellStack.contains(cellSpec)) {
			cellStack.push(cellSpec);
			if (MainService.DEBUG) {
				Log.d(TAG, "queued " + cellSpec + " for retrieval");
			}
		}
		synchronized (loopThread) {
			loopThread.notifyAll();
		}
	}

	public void queueLocationRetrieval(WifiSpec wifiSpec) {
		if (!wifiStack.contains(wifiSpec)) {
			wifiStack.push(wifiSpec);
			if (MainService.DEBUG) {
				Log.d(TAG, "queued " + wifiSpec + " for retrieval");
			}
		}
		synchronized (loopThread) {
			loopThread.notifyAll();
		}
	}

	public <T extends PropSpec> void queueLocationRetrieval(T spec) {
		if (spec instanceof CellSpec) {
			CellSpec cellSpec = (CellSpec) spec;
			queueLocationRetrieval(cellSpec);
		} else if (spec instanceof WifiSpec) {
			WifiSpec wifiSpec = (WifiSpec) spec;
			queueLocationRetrieval(wifiSpec);
		} else {
			throw new IllegalArgumentException("spec must be Cell or Wifi spec");
		}
	}

	private void retrieveLocations(int threadCount) {
		if (cellStack.isEmpty() && wifiStack.isEmpty()) {
			return;
		}
		if (MainService.DEBUG) {
			Log.d(TAG, "retrieving with " + threadCount + " threads");
		}
		Collection<Thread> threads = new ArrayList<Thread>();
		while (threadCount-- > 0) {
			Thread t = new Thread(new Runnable() {
				@Override
				public void run() {
					retrieveLocations(cellStack, 10, cellLocationSources);
				}
			});
			threads.add(t);
			t.start();
			t = new Thread(new Runnable() {
				@Override
				public void run() {
					retrieveLocations(wifiStack, 10, wifiLocationSources);
				}
			});
			threads.add(t);
			t.start();
		}
		try {
			for (Thread thread : threads) {
				thread.join();
			}
		} catch (InterruptedException e) {
			Log.w(TAG, e);
		}

	}

	private <T extends PropSpec> void retrieveLocations(Deque<T> stack, int num,
														List<? extends LocationSource<T>> locationSources) {
		if (!stack.isEmpty()) {
			Collection<T> specs = new ArrayList<T>();
			while (!stack.isEmpty() && (num-- > 0)) {
				T pop = stack.pop();
				if (locationDatabase.get(pop) == null) {
					specs.add(pop);
				} else {
					++num;
				}
			}
			retrieveLocations(locationSources, specs);
		}
	}

	private <T extends PropSpec> void retrieveLocations(List<? extends LocationSource<T>> locationSources,
														Collection<T> todo) {
		for (LocationSource<T> locationSource : locationSources) {
			if (locationSource.isSourceAvailable()) {
				if (MainService.DEBUG) {
					Log.d(TAG, "Retrieving " + todo.size() + " locations from " + locationSource);
				}
				try {
					for (LocationSpec<T> locationSpec : locationSource.retrieveLocation(todo)) {
						locationDatabase.put(locationSpec);
						todo.remove(locationSpec.getSource());
					}
					if (todo.isEmpty()) {
						break;
					}
				} catch (Throwable t) {
					Log.d(TAG, locationSource.getName() + " caused problem!", t);
				}
			} else if (MainService.DEBUG) {
				Log.d(TAG, locationSource.getName() + " is currently not available");
			}
		}
		for (T spec : todo) {
			locationDatabase.put(new LocationSpec<T>(spec));
		}
		todo.clear();
	}

	private void retrieveLoop() {
		while (!Thread.interrupted()) {
			long time = System.currentTimeMillis();
			if (lastRetrieve < (time - WAIT_BETWEEN)) {
				retrieveLocations(1);
				lastRetrieve = time;
			}
			synchronized (loopThread) {
				try {
					if (cellStack.isEmpty() && wifiStack.isEmpty()) {
						loopThread.wait();
					} else {
						loopThread.wait((lastRetrieve + WAIT_BETWEEN) - time);
					}
				} catch (InterruptedException e) {
					Log.w(TAG, e);
					return;
				}
			}
		}
	}

	public void start() {
		loopThread.start();
	}

	public void stop() {
		loopThread.interrupt();
	}
}
