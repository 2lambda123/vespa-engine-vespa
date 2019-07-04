// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/searchlib/memoryindex/word_store.h>
#include <vespa/vespalib/gtest/gtest.h>

#include <vespa/log/log.h>
LOG_SETUP("word_store_test");

using namespace search::datastore;

namespace search::memoryindex {

TEST(WordStoreTest, words_can_be_added_and_retrieved)
{
    std::string w1 = "require";
    std::string w2 = "that";
    std::string w3 = "words";
    WordStore ws;
    EntryRef r1 = ws.addWord(w1);
    EntryRef r2 = ws.addWord(w2);
    EntryRef r3 = ws.addWord(w3);
    uint32_t invp = WordStore::RefType::align(1);   // Reserved as invalid
    uint32_t w1s = w1.size() + 1;
    uint32_t w1p = WordStore::RefType::pad(w1s);
    uint32_t w2s = w2.size() + 1;
    uint32_t w2p = WordStore::RefType::pad(w2s);
    EXPECT_EQ(invp, WordStore::RefType(r1).offset());
    EXPECT_EQ(invp + w1s + w1p, WordStore::RefType(r2).offset());
    EXPECT_EQ(invp + w1s + w1p + w2s + w2p, WordStore::RefType(r3).offset());
    EXPECT_EQ(0u, WordStore::RefType(r1).bufferId());
    EXPECT_EQ(0u, WordStore::RefType(r2).bufferId());
    EXPECT_EQ(0u, WordStore::RefType(r3).bufferId());
    EXPECT_EQ(std::string("require"), ws.getWord(r1));
    EXPECT_EQ(std::string("that"), ws.getWord(r2));
    EXPECT_EQ(std::string("words"), ws.getWord(r3));
}

TEST(WordStoreTest, add_word_triggers_change_of_buffer)
{
    WordStore ws;
    size_t word = 0;
    uint32_t lastId = 0;
    char wordStr[10];
    for (;;++word) {
        sprintf(wordStr, "%6zu", word);
        // all words uses 12 bytes (include padding)
        EntryRef r = ws.addWord(std::string(wordStr));
        EXPECT_EQ(std::string(wordStr), ws.getWord(r));
        uint32_t bufferId = WordStore::RefType(r).bufferId();
        if (bufferId > lastId) {
            LOG(info,
                "Changed to bufferId %u after %zu words",
                bufferId, word);
            lastId = bufferId;
        }
        if (bufferId == 4) {
            lastId = bufferId;
            break;
        }
    }
    LOG(info, "Added %zu words in 4 buffers", word);
    EXPECT_EQ(2047u, word);
    EXPECT_EQ(4u, lastId);
}

}

GTEST_MAIN_RUN_ALL_TESTS()

