package ali.paf.contacts

import android.content.Context
import ali.paf.contacts.account.AccountRepository
import ali.paf.contacts.data.CardDavDiscovery
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the application [Context].
     * Hilt already has this via @ApplicationContext, but exposing it here
     * satisfies any constructor injection of plain Context.
     */
    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context = context

    /**
     * Provides the [AccountRepository].
     * Already annotated with @Singleton + @Inject, but listed here for clarity.
     */
    @Provides
    @Singleton
    fun provideAccountRepository(@ApplicationContext context: Context): AccountRepository =
        AccountRepository(context)
}
