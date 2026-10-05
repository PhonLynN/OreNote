package com.phonlynn.oreplan.domain.sync

import com.phonlynn.oreplan.domain.sync.adapter.AttachmentSyncAdapter
import com.phonlynn.oreplan.domain.sync.adapter.BoardCardSyncAdapter
import com.phonlynn.oreplan.domain.sync.adapter.BoardTagSyncAdapter
import com.phonlynn.oreplan.domain.sync.adapter.CourseSyncAdapter
import com.phonlynn.oreplan.domain.sync.adapter.FocusSessionSyncAdapter
import com.phonlynn.oreplan.domain.sync.adapter.ItemSyncAdapter
import com.phonlynn.oreplan.domain.sync.adapter.TagSyncAdapter
import com.phonlynn.oreplan.domain.sync.adapter.TermSyncAdapter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 同步适配器的装配。
 *
 * ## 为什么用 `@Provides` 而不是 Dagger 的 `@IntoSet` 多绑定
 *
 * 试过 `@IntoSet` + 构造注入 `Set<SyncTableAdapter>`，**构建失败**：
 * 当前版本的 Dagger KSP 处理器在解析"构造注入的 Set 参数"时抛
 * `ClassCastException`（KspTypeElement 无法转成 XExecutableElement）——
 * 是工具链的问题，不是用法的问题。
 *
 * 与其为了一个更"优雅"的写法去升级/绕过工具链，不如写一个显式列表：
 *  · 装配关系**一眼看得全**（哪些表参与同步，这里就是答案）；
 *  · 新增一张表时只改这一处，不可能漏；
 *  · 不引任何新依赖、不动构建配置。
 *
 * ## 顺序
 *
 * 顺序**不影响正确性**（各表独立同步），但影响用户在同步过程中的观感：
 * 先条目（今日/日程/规划，最常看），再白板，最后专注记录。
 */
@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    /**
     * 附件二进制读写。
     *
     * 显式提供而不是靠 `@Inject` 构造：`BlobStore` 的分片上传器**有默认值**
     * （为了让单元测试能直接 `BlobStore()` 构造），而 Dagger 不支持
     * "带默认值的构造参数"——它会认为这个构造缺一个绑定。
     * 这里显式 new 出来，两边都满足。
     */
    @Provides
    @Singleton
    fun provideBlobStore(): com.phonlynn.oreplan.domain.sync.blob.BlobStore =
        com.phonlynn.oreplan.domain.sync.blob.BlobStore()

    @Provides
    @Singleton
    fun provideSyncTableAdapters(
        itemAdapter: ItemSyncAdapter,
        boardCardAdapter: BoardCardSyncAdapter,
        boardTagAdapter: BoardTagSyncAdapter,
        courseAdapter: CourseSyncAdapter,
        tagAdapter: TagSyncAdapter,
        termAdapter: TermSyncAdapter,
        focusAdapter: FocusSessionSyncAdapter,
        attachmentAdapter: AttachmentSyncAdapter,
    ): List<@JvmSuppressWildcards SyncTableAdapter> = listOf(
        itemAdapter,
        boardCardAdapter,
        boardTagAdapter,
        courseAdapter,
        tagAdapter,
        termAdapter,
        focusAdapter,
        // 附件索引放最后：它最小、但数量最多（每张图一行），
        // 放在最后能让用户更早看到主要数据的同步结果。
        attachmentAdapter,
    )
}
