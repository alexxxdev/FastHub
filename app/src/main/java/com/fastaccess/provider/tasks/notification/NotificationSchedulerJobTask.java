package com.fastaccess.provider.tasks.notification;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;

import com.annimon.stream.Stream;
import com.fastaccess.R;
import com.fastaccess.data.dao.model.Comment;
import com.fastaccess.data.dao.model.Login;
import com.fastaccess.data.dao.model.Notification;
import com.fastaccess.helper.AppHelper;
import com.fastaccess.helper.BundleConstant;
import com.fastaccess.helper.InputHelper;
import com.fastaccess.helper.Logger;
import com.fastaccess.helper.PrefGetter;
import com.fastaccess.helper.RxHelper;
import com.fastaccess.provider.rest.RestProvider;

import java.util.List;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.schedulers.Schedulers;

/**
 * Created by Kosh on 19 Feb 2017, 6:32 PM
 */

public class NotificationSchedulerJobTask extends JobService {
    private final static int JOB_ID_EVERY_30_MINS = 1;
    private final static long THIRTY_MINUTES = TimeUnit.MINUTES.toMillis(30);
    private static final String NOTIFICATION_GROUP_ID = "FastHub";

    @Override public boolean onStartJob(JobParameters job) {
        if (Login.getUser() != null) {
            RestProvider.getNotificationService()
                    .getNotifications(0)
                    .subscribeOn(Schedulers.io())
                    .subscribe(item -> {
                        AppHelper.cancelAllNotifications(getApplicationContext());
                        if (item != null) {
                            onSave(item.getItems(), job);
                        } else {
                            finishJob(job);
                        }
                        scheduleJob(getApplicationContext());
                    }, throwable -> finishJob(job));
        } else {
            finishJob(job);
        }
        return true;
    }

    @Override public boolean onStopJob(JobParameters job) {
        return false;
    }

    public static void scheduleJob(@NonNull Context context) {
        long duration = PrefGetter.getNotificationTaskDuration(context);
        scheduleJob(context, duration == 0 ? THIRTY_MINUTES : duration, false);
    }

    public static void scheduleJob(@NonNull Context context, long duration, boolean cancel) {
        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (cancel) jobScheduler.cancel(JOB_ID_EVERY_30_MINS);
        if (duration == -1) {
            jobScheduler.cancel(JOB_ID_EVERY_30_MINS);
            return;
        }
        duration = duration <= 0 ? THIRTY_MINUTES : duration;
        JobInfo.Builder builder = new JobInfo.Builder(JOB_ID_EVERY_30_MINS, new ComponentName(context.getPackageName(),
                NotificationSchedulerJobTask.class.getName()))
                .setBackoffCriteria(JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS, JobInfo.BACKOFF_POLICY_LINEAR)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && duration < JobInfo.getMinPeriodMillis()) {
            builder.setMinimumLatency(duration);
        } else {
            builder.setPeriodic(duration);
        }
        jobScheduler.schedule(builder.build());
    }

    private void onSave(@Nullable List<Notification> notificationThreadModels, JobParameters job) {
        if (notificationThreadModels != null) {
            RxHelper.safeObservable(Notification.save(notificationThreadModels)).subscribe();
            onNotifyUser(notificationThreadModels, job);
        }
    }

    private void onNotifyUser(@NonNull List<Notification> notificationThreadModels, JobParameters job) {
        long count = Stream.of(notificationThreadModels)
                .filter(Notification::isUnread)
                .count();
        if (count == 0) {
            AppHelper.cancelAllNotifications(getApplicationContext());
            return;
        }
        Context context = getApplicationContext();
        int accentColor = ContextCompat.getColor(this, R.color.material_blue_700);
        Bitmap largeIcon = BitmapFactory.decodeResource(context.getResources(),
                R.mipmap.ic_launcher);
        Observable.from(notificationThreadModels)
                .subscribeOn(Schedulers.io())
                .filter(Notification::isUnread)
                .limit(10)
                .flatMap(notification -> RestProvider.getNotificationService()
                        .getComment(notification.getSubject().getLatestCommentUrl())
                        .subscribeOn(Schedulers.io()), (thread, comment) -> {
                    if (!InputHelper.isEmpty(thread.getSubject().getLatestCommentUrl())) {
                        android.app.Notification toAdd = getNotificationWithComment(context, accentColor, largeIcon, thread, comment);
                        showNotification((int) comment.getId(), toAdd);
                        return null;
                    }
                    return thread;
                })
                .subscribeOn(Schedulers.io())
                .subscribe(thread -> {
                    if (thread != null) {
                        showNotificationWithoutComment(context, accentColor, thread, largeIcon);
                    }
                }, throwable -> finishJob(job), () -> {
                    Logger.e();
                    android.app.Notification grouped = getSummaryGroupNotification(accentColor);
                    showNotification(BundleConstant.REQUEST_CODE, grouped);
                    finishJob(job);
                });
    }

    private void finishJob(JobParameters job) {
        jobFinished(job, false);
    }

    private void showNotificationWithoutComment(Context context, int accentColor, Notification thread, Bitmap largeIcon) {
        android.app.Notification toAdd = getNotification(thread.getSubject().getTitle(), thread.getRepository().getFullName())
                .setLargeIcon(largeIcon)
                .setContentIntent(getPendingIntent(thread.getId(), thread.getSubject().getUrl()))
                .addAction(R.drawable.ic_github, context.getString(R.string.open), getPendingIntent(thread.getId(), thread
                        .getSubject().getUrl()))
                .addAction(R.drawable.ic_eye_off, context.getString(R.string.mark_as_read), getReadOnlyPendingIntent(thread.getId(), thread
                        .getSubject().getUrl()))
                .setWhen(thread.getUpdatedAt() != null ? thread.getUpdatedAt().getTime() : System.currentTimeMillis())
                .setShowWhen(true)
                .setColor(accentColor)
                .setGroup(NOTIFICATION_GROUP_ID)
                .build();
        showNotification((int) thread.getId(), toAdd);
    }

    private android.app.Notification getNotificationWithComment(Context context, int accentColor, Bitmap largeIcon,
                                                                Notification thread, Comment comment) {
        return getNotification(comment.getUser() != null ? comment.getUser().getLogin() : "", comment.getBody())
                .setSmallIcon(R.drawable.ic_notification)
                .setLargeIcon(largeIcon)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .setBigContentTitle(comment.getUser() != null ? comment.getUser().getLogin() : "")
                        .bigText(comment.getBody()))
                .setWhen(comment.getCreatedAt().getTime())
                .setShowWhen(true)
                .addAction(R.drawable.ic_github, context.getString(R.string.open), getPendingIntent(thread.getId(),
                        thread.getSubject().getUrl()))
                .addAction(R.drawable.ic_eye_off, context.getString(R.string.mark_as_read), getReadOnlyPendingIntent(thread.getId(),
                        thread.getSubject().getUrl()))
                .setContentIntent(getPendingIntent(thread.getId(), thread.getSubject().getUrl()))
                .setColor(accentColor)
                .setGroup(NOTIFICATION_GROUP_ID)
                .build();
    }

    private android.app.Notification getSummaryGroupNotification(int accentColor) {
        return getNotification(getString(R.string.notifications), getString(R.string.unread_notification))
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(accentColor)
                .setGroup(NOTIFICATION_GROUP_ID)
                .setGroupSummary(true)
                .build();
    }

    private NotificationCompat.Builder getNotification(@NonNull String title, @NonNull String message) {
        return new NotificationCompat.Builder(this)
                .setContentTitle(title)
                .setContentText(message)
                .setAutoCancel(true);
    }

    private void showNotification(int id, android.app.Notification notification) {
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify(id, notification);
    }

    private PendingIntent getReadOnlyPendingIntent(long id, @NonNull String url) {
        Intent intent = ReadNotificationService.start(this, id, url, true);
        return PendingIntent.getService(this, InputHelper.getSafeIntId(id) / 2, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent getPendingIntent(long id, @NonNull String url) {
        Intent intent = ReadNotificationService.start(this, id, url);
        return PendingIntent.getService(this, InputHelper.getSafeIntId(id), intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
