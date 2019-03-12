// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include <vespa/vespalib/stllike/string.h>
#include <cstdint>

namespace vespalib {

template<typename K> struct hash {
    // specializations operate as functor for known key types
    size_t operator() (const K & v) const {
        return v.hash();
    }
};

template<> struct hash<char> {
    size_t operator() (char arg) const { return arg; }
};
template<> struct hash<signed char> {
    size_t operator() (signed char arg) const { return arg; }
};
template<> struct hash<short> {
    size_t operator() (short arg) const { return arg; }
};
template<> struct hash<int> {
    size_t operator() (int arg) const { return arg; }
};
template<> struct hash<long> {
    size_t operator() (long arg) const { return arg; }
};
template<> struct hash<long long> {
    size_t operator() (long long arg) const { return arg; }
};

template<> struct hash<unsigned char> {
    size_t operator() (unsigned char arg) const { return arg; }
};
template<> struct hash<unsigned short> {
    size_t operator() (unsigned short arg) const { return arg; }
};
template<> struct hash<unsigned int> {
    size_t operator() (unsigned int arg) const { return arg; }
};
template<> struct hash<unsigned long> {
    size_t operator() (unsigned long arg) const { return arg; }
};
template<> struct hash<unsigned long long> {
    size_t operator() (unsigned long long arg) const { return arg; }
};

template<> struct hash<float> {
    union U { float f; uint32_t i; };
    size_t operator() (float arg) const { U t; t.f = arg; return t.i; }
};
template<> struct hash<double> {
    union U { double f; uint64_t i; };
    size_t operator() (double arg) const { U t; t.f = arg; return t.i; }
};

template<typename T> struct hash<T *> {
    size_t operator() (const T * arg) const { return size_t(arg); }
};
template<typename T> struct hash<const T *> {
    size_t operator() (const T * arg) const { return size_t(arg); }
};

// reuse old string hash function
size_t hashValue(const char *str);
size_t hashValue(const void *str, size_t sz);

struct hash_strings {
    size_t operator() (const vespalib::string & arg) const { return hashValue(arg.c_str()); }
    size_t operator() (vespalib::stringref arg) const { return hashValue(arg.data(), arg.size()); }
    size_t operator() (const char * arg) const { return hashValue(arg); }
    size_t operator() (const std::string& arg) const { return hashValue(arg.c_str()); }
};

template<> struct hash<const char *> : hash_strings { };
template<> struct hash<vespalib::stringref> : public hash_strings { };
template<> struct hash<vespalib::string> : hash_strings {};
template<> struct hash<std::string> : hash_strings {};

template<typename V> struct size {
    size_t operator() (const V & arg) const { return arg.size(); }
};

template<typename V> struct zero {
    size_t operator() (const V & ) const { return 0; }
};

} // namespace vespalib
