package com.phonlynn.oreplan.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.phonlynn.oreplan.data.local.entity.BoardCardEntity
import com.phonlynn.oreplan.data.local.entity.BoardCardLinkEntity
import com.phonlynn.oreplan.data.local.entity.BoardCardTagEntity
import com.phonlynn.oreplan.data.local.entity.BoardTagEntity
import com.phonlynn.oreplan.data.local.entity.BoardTodoItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BoardCardDao {

    @Query("SELECT * FROM board_cards WHERE archived = 0 ORDER BY pinned DESC, updatedAt DESC")
    fun observeActive(): Flow<List<BoardCardEntity>>

    @Query("SELECT * FROM board_cards")
    suspend fun findAll(): List<BoardCardEntity>

    @Query("DELETE FROM board_cards")
    suspend fun clearAll()

    @Query("SELECT * FROM board_cards WHERE archived = 1 ORDER BY updatedAt DESC")
    fun observeArchived(): Flow<List<BoardCardEntity>>

    @Query("SELECT * FROM board_cards WHERE id = :id")
    fun observeById(id: String): Flow<BoardCardEntity?>

    @Query("SELECT * FROM board_cards WHERE id = :id")
    suspend fun findById(id: String): BoardCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: BoardCardEntity)

    @Query("DELETE FROM board_cards WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface BoardTodoItemDao {

    @Query("SELECT * FROM board_todo_items ORDER BY sortIndex ASC")
    fun observeAll(): Flow<List<BoardTodoItemEntity>>

    @Query("SELECT * FROM board_todo_items")
    suspend fun findAll(): List<BoardTodoItemEntity>

    @Query("DELETE FROM board_todo_items")
    suspend fun clearAll()

    @Query("SELECT * FROM board_todo_items WHERE cardId = :cardId ORDER BY sortIndex ASC")
    suspend fun listByCard(cardId: String): List<BoardTodoItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<BoardTodoItemEntity>)

    @Query("UPDATE board_todo_items SET done = :done WHERE id = :id")
    suspend fun setDone(id: String, done: Boolean)

    @Query("DELETE FROM board_todo_items WHERE cardId = :cardId")
    suspend fun deleteByCard(cardId: String)
}

@Dao
interface BoardTagDao {

    @Query("SELECT * FROM board_tags ORDER BY sortIndex ASC, name ASC")
    fun observeAll(): Flow<List<BoardTagEntity>>

    @Query("DELETE FROM board_tags")
    suspend fun clearAll()

    @Query("SELECT * FROM board_tags ORDER BY sortIndex ASC, name ASC")
    suspend fun listAll(): List<BoardTagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(tag: BoardTagEntity)

    @Query("UPDATE board_tags SET parentId = :parentId WHERE parentId = :id")
    suspend fun reparentChildren(id: String, parentId: String?)

    @Query("DELETE FROM board_tags WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface BoardCardTagDao {

    @Query("SELECT * FROM board_card_tags")
    fun observeAll(): Flow<List<BoardCardTagEntity>>

    @Query("SELECT * FROM board_card_tags")
    suspend fun findAll(): List<BoardCardTagEntity>

    @Query("DELETE FROM board_card_tags")
    suspend fun clearAll()

    @Query("SELECT * FROM board_card_tags WHERE cardId = :cardId")
    suspend fun listByCard(cardId: String): List<BoardCardTagEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(links: List<BoardCardTagEntity>)

    @Query("DELETE FROM board_card_tags WHERE cardId = :cardId")
    suspend fun deleteByCard(cardId: String)

    @Query("DELETE FROM board_card_tags WHERE tagId = :tagId")
    suspend fun deleteByTag(tagId: String)
}

@Dao
interface BoardCardLinkDao {

    @Query("SELECT * FROM board_card_links")
    fun observeAll(): Flow<List<BoardCardLinkEntity>>

    @Query("SELECT * FROM board_card_links")
    suspend fun findAll(): List<BoardCardLinkEntity>

    @Query("DELETE FROM board_card_links")
    suspend fun clearAll()

    @Query("SELECT * FROM board_card_links WHERE cardId = :cardId")
    suspend fun listByCard(cardId: String): List<BoardCardLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(links: List<BoardCardLinkEntity>)

    @Query("DELETE FROM board_card_links WHERE cardId = :cardId")
    suspend fun deleteByCard(cardId: String)

    @Query("DELETE FROM board_card_links WHERE linkedCardId = :cardId OR cardId = :cardId")
    suspend fun deleteInvolving(cardId: String)
}
