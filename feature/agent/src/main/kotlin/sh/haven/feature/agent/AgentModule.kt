package sh.haven.feature.agent

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import sh.haven.feature.agent.provider.LlmClient
import sh.haven.feature.agent.provider.OpenAiCompatibleClient
import javax.inject.Singleton

/**
 * Hilt module for the Catty Agent feature. Provides the isolated
 * OkHttpClient (long read timeout for SSE streaming) and binds
 * [OpenAiCompatibleClient] as the [LlmClient] implementation.
 */
@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideAgentOkHttpClient(): OkHttpClient =
        OpenAiCompatibleClient.createOkHttpClient()

    @Provides
    @Singleton
    fun provideLlmClient(impl: OpenAiCompatibleClient): LlmClient = impl
}
