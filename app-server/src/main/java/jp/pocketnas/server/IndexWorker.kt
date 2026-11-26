package jp.pocketnas.server

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * ★Index-3: アイドル時の自動インデックス
 *
 * - 充電中 かつ 端末アイドル のときだけ、1日1回フルスキャンする定期ジョブ。
 * - サーバー本体（フォアグラウンドサービス）が止まっていても動く。
 * - 手動実行は /api/index/rebuild（NasServerService）から IndexScanner を直接呼ぶ。
 */
class IndexWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        // サービス側のスキャンと競合したら、定期ジョブ側はやり直しを OS に任せる
        if (IndexScanner.isScanning()) return Result.retry()
        return if (IndexScanner.runFullScan(applicationContext)) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_NAME = "pocketnas-index-periodic"

        /** 定期ジョブを登録する（登録済みなら何もしない）。MainActivity / サービス起動時に呼ぶ。 */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .build()
            val request = PeriodicWorkRequestBuilder<IndexWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
