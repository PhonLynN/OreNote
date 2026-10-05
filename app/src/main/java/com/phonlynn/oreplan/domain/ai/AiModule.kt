package com.phonlynn.oreplan.domain.ai

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AI 层的装配。
 *
 * ## 为什么 `AiClient` 要显式提供而不是靠 `@Inject` 构造
 *
 * `AiClient` 的构造参数 `transport` **有默认值** ——
 * 那是为了让单元测试能直接 `AiClient()` 构造（不传 transport 就是真实网络）。
 * 但 Dagger 不支持"带默认值的构造参数"：它会认为这个构造缺一个绑定。
 *
 * 这与 S5 的 `BlobStore` 是同一个问题、同一个解法。
 * 之所以在这里再写一遍注释，是因为这个报错（`Dagger/MissingBinding`）
 * 看起来像是"忘了加 @Inject"，而实际原因在默认值上 —— 不知道的话会查很久。
 */
@Module
@InstallIn(SingletonComponent::class)
object AiModule {

    @Provides
    @Singleton
    fun provideAiClient(): AiClient = AiClient()
}
