package com.example.voicenotes.di

import android.content.Context
import androidx.room.Room
import com.example.voicenotes.data.local.AppDatabase
import com.example.voicenotes.data.remote.OpenAIApi
import com.example.voicenotes.data.repository.MeetingRepository
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.MultipartBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@dagger.hilt.android.qualifiers.ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "notes_db").addMigrations(AppDatabase.MIGRATION_1_2).build()
    }

    @Provides
    fun provideMeetingDao(db: AppDatabase) = db.meetingDao()

    @Provides
    fun provideTranscriptDao(db: AppDatabase) = db.transcriptDao()

    @Provides @Singleton
    fun provideOpenAIApi(): OpenAIApi {
        // OkHttp client
        val client = OkHttpClient.Builder().build()
        return Retrofit.Builder()
            .baseUrl("https://api.openai.com/v1/")    // Base API URL for OpenAI
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenAIApi::class.java)
    }

    @Provides @Singleton
    fun provideRepository(meetingDao: com.example.voicenotes.data.local.MeetingDao,
                          transcriptDao: com.example.voicenotes.data.local.TranscriptDao,
                          openAIApi: OpenAIApi
    ): MeetingRepository {
        return MeetingRepository(meetingDao, transcriptDao, openAIApi)
    }
}
