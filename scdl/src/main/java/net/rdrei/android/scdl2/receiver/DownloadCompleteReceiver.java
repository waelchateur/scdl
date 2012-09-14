package net.rdrei.android.scdl2.receiver;

import java.io.File;
import java.io.IOException;

import net.rdrei.android.scdl2.ApplicationPreferences;
import net.rdrei.android.scdl2.Config;
import net.rdrei.android.scdl2.IOUtil;
import net.rdrei.android.scdl2.R;
import net.rdrei.android.scdl2.service.MediaScannerService;
import roboguice.receiver.RoboBroadcastReceiver;
import roboguice.util.Ln;
import roboguice.util.RoboAsyncTask;
import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.support.v4.app.NotificationCompat;

import com.google.inject.Inject;

/**
 * Broadcast receiver reacting on download finished events.
 * 
 * @author pascal
 * 
 */
public class DownloadCompleteReceiver extends RoboBroadcastReceiver {

	@Inject
	private DownloadManager mDownloadManager;

	@Inject
	private NotificationManager mNotificationManager;

	/**
	 * Simple POJO for passing around download information.
	 * 
	 * @author pascal
	 * 
	 */
	private class Download {
		private String mTitle;
		private String mPath;
		private int mStatus;

		public String getTitle() {
			return mTitle;
		}

		public void setTitle(final String title) {
			mTitle = title;
		}

		/**
		 * This is not used right now, but should be to customize the
		 * notification in case of an error.
		 * 
		 * @return
		 */
		@SuppressWarnings("unused")
		public int getStatus() {
			return mStatus;
		}

		public void setStatus(final int status) {
			mStatus = status;
		}

		public String getPath() {
			return mPath;
		}

		public void setPath(final String path) {
			mPath = path;
		}
	}

	@Override
	public void handleReceive(final Context context, final Intent intent) {
		final long downloadId = intent.getLongExtra(
				DownloadManager.EXTRA_DOWNLOAD_ID, 0);
		final ResolveDownloadTask task = new ResolveDownloadTask(context,
				downloadId);
		task.execute();
	}

	/**
	 * @param context
	 * @param title
	 */
	private void showNotification(final Context context, final String title) {
		final Intent downloadIntent = new Intent(
				DownloadManager.ACTION_VIEW_DOWNLOADS);

		@SuppressWarnings("deprecation")
		final Notification notification = new NotificationCompat.Builder(
				context)
				.setAutoCancel(true)
				.setContentTitle(
						context.getString(R.string.notification_download_finished))
				.setContentText(title)
				.setTicker(
						String.format(
								context.getString(R.string.notification_download_finished_ticker),
								title))
				.setSmallIcon(android.R.drawable.stat_sys_download_done)
				.setContentIntent(
						PendingIntent
								.getActivity(context, 0, downloadIntent, 0))
				.getNotification();

		mNotificationManager.notify(0, notification);
	}

	private class ResolveDownloadTask extends RoboAsyncTask<Download> {
		private final long mDownloadId;

		public ResolveDownloadTask(final Context context, final long downloadId) {
			super(context);

			mDownloadId = downloadId;
		}

		@Override
		public Download call() throws Exception {
			final Query query = new DownloadManager.Query();
			query.setFilterById(mDownloadId);
			final Cursor cursor = mDownloadManager.query(query);

			try {
				if (!cursor.moveToFirst()) {
					// Download could not be found.
					Ln.d("Could not find download with id %d.", mDownloadId);
					return null;
				}

				final int descriptionIndex = cursor
						.getColumnIndex(DownloadManager.COLUMN_DESCRIPTION);

				if (!cursor.getString(descriptionIndex).equals(
						context.getString(R.string.download_description))) {
					// Download doesn't belong to us. Weird way to check, but
					// way,
					// way
					// easier than keeping track of the IDs.
					Ln.d("Description did not match SCDL default description.");
					return null;
				}

				final int titleIndex = cursor
						.getColumnIndex(DownloadManager.COLUMN_TITLE);
				final String title = cursor.getString(titleIndex);

				final int statusIndex = cursor
						.getColumnIndex(DownloadManager.COLUMN_STATUS);
				final int status = cursor.getInt(statusIndex);

				final int localUriIndex = cursor
						.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
				final String downloadUri = cursor.getString(localUriIndex);

				final Download download = new Download();
				download.setTitle(title);
				download.setStatus(status);
				download.setPath(downloadUri);

				return download;
			} finally {
				cursor.close();
			}
		}

		@Override
		protected void onSuccess(final Download t) throws Exception {
			super.onSuccess(t);

			if (t == null) {
				return;
			}

			if (shouldMoveFileToLocal(t)) {
				Ln.d("Moving temporary file to local location.");
				moveFileToLocal(t);
			}

			final Intent scanIntent = new Intent(context,
					MediaScannerService.class);
			scanIntent.putExtra(MediaScannerService.EXTRA_PATH, t.getPath());
			context.startService(scanIntent);

			showNotification(context, t.getTitle());
		}

		protected boolean shouldMoveFileToLocal(final Download download) {
			return download.getPath().endsWith(Config.TMP_DOWNLOAD_POSTFIX);
		}

		/**
		 * Moves a download to a local location and removes the temporary path
		 * suffix.
		 * 
		 * @param download
		 */
		protected void moveFileToLocal(final Download download) {
			final File path = new File(download.getPath().substring(
					"file:".length()));
			final String filename = path.getName();
			final File newDir = context.getDir(
					ApplicationPreferences.DEFAULT_STORAGE_DIRECTORY,
					Context.MODE_WORLD_READABLE);

			final String newFileName = path.getName().substring(0,
					filename.length() - Config.TMP_DOWNLOAD_POSTFIX.length());

			final File newPath = new File(newDir, newFileName);
			try {
				IOUtil.copyFile(path, newPath);
			} catch (final IOException err) {
				Ln.w(err, "Failed to rename download.");
				return;
			}

			Ln.d("Download moved to %s", newPath.toString());
			path.delete();
			download.setPath(newPath.getAbsolutePath());
		}
	}
}