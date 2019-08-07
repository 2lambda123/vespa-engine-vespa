// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "idstring.h"
#include "idstringexception.h"
#include <vespa/document/bucket/bucketid.h>
#include <vespa/vespalib/util/md5.h>
#include <vespa/vespalib/util/stringfmt.h>
#include <limits>
#include <cerrno>

using vespalib::string;
using vespalib::stringref;
using vespalib::make_string;

namespace document {

VESPA_IMPLEMENT_EXCEPTION(IdParseException, vespalib::Exception);

namespace {

string _G_typeName[6] = {
    "doc",
    "userdoc",
    "groupdoc",
    "id",
    "null"
};

}

const string &
IdString::getTypeName(Type t)
{
    return _G_typeName[t];
}

string
IdString::getSchemeName() const
{
    return getTypeName(getType());
}

const string &
IdString::toString() const
{
    return _rawId;
}

namespace {

void reportError(const char* part) __attribute__((noinline));
void reportError(stringref s, const char* part) __attribute__((noinline));
void reportTooShortDocId(const char * id, size_t sz) __attribute__((noinline));
void reportNoSchemeSeparator(const char * id) __attribute__((noinline));

void reportError(const char* part)
{
    throw IdParseException(make_string("Unparseable id: No %s separator ':' found", part), VESPA_STRLOC);
}
void reportError(stringref s, const char* part)
{
    throw IdParseException(make_string("Unparseable %s '%s': Not an unsigned 64-bit number", part, string(s).c_str()), VESPA_STRLOC);
}

void reportNoSchemeSeparator(const char * id)
{
    throw IdParseException(make_string("Unparseable id '%s': No scheme separator ':' found", id), VESPA_STRLOC);
}

void reportTooShortDocId(const char * id, size_t sz)
{
    throw IdParseException( make_string( "Unparseable id '%s': It is too short(%li) " "to make any sense", id, sz), VESPA_STRLOC);
}

uint64_t getAsNumber(stringref s, const char* part) {
    char* errPos = NULL;
    uint64_t value = strtoull(s.data(), &errPos, 10);

    if (s.data() + s.size() != errPos) {
        reportError(s, part);
    }
    return value;
}

union TwoByte {
    char     asChar[2];
    uint16_t as16;
};

union FourByte {
    char     asChar[4];
    uint32_t as32;
};

union EightByte {
    char     asChar[8];
    uint64_t as64;
};

const FourByte _G_doc = {{'d', 'o', 'c', ':'}};
const FourByte _G_null = {{'n', 'u', 'l', 'l'}};
const EightByte _G_userdoc = {{'u', 's', 'e', 'r', 'd', 'o', 'c', ':'}};
const EightByte _G_groupdoc = {{'g', 'r', 'o', 'u', 'p', 'd', 'o', 'c'}};
const TwoByte _G_id = {{'i', 'd'}};

typedef char v16qi __attribute__ ((__vector_size__(16)));

v16qi _G_zero  = { ':', ':', ':', ':', ':', ':', ':', ':', ':', ':', ':', ':', ':', ':', ':', ':' };

//const char * fmemchr_stdc(const char * s, const char * e) __attribute__((noinline));

inline const char *
fmemchr(const char * s, const char * e)
{
    while (s+15 < e) {
#ifdef __clang__
        v16qi tmpCurrent = __builtin_ia32_lddqu(s);
        v16qi tmp0       = tmpCurrent == _G_zero;
#else
        v16qi tmpCurrent = __builtin_ia32_loaddqu(s);
        v16qi tmp0       = __builtin_ia32_pcmpeqb128(tmpCurrent, _G_zero);
#endif
        uint32_t charMap = __builtin_ia32_pmovmskb128(tmp0); // 1 in charMap equals to '\0' in input buffer
        if (__builtin_expect(charMap, 1)) {
            return s + vespalib::Optimized::lsbIdx(charMap);
        }
        s+=16;
    }

    const char c(':');
    while (s+3 < e) {
        if (s[0] == c) {
            return s;
        }
        if (s[1] == c) {
            return s+1;
        }
        if (s[2] == c) {
            return s+2;
        }
        if (s[3] == c) {
            return s+3;
        }
        s+=4;
    }
    while (s < e) {
        if (s[0] == c) {
            return s;
        }
        s++;
    }
    return NULL;
}

}  // namespace


IdString::Offsets::Offsets(uint32_t maxComponents, uint32_t namespaceOffset, stringref id)
{
    _offsets[0] = namespaceOffset;
    size_t index(1);
    const char * s(id.data() + namespaceOffset);
    const char * e(id.data() + id.size());
    for(s=fmemchr(s, e);
        (s != NULL) && (index < maxComponents);
        s = fmemchr(s+1, e))
    {
        _offsets[index++] = s - id.data() + 1;
    }
    _numComponents = index;
    for (;index < VESPA_NELEMS(_offsets); index++) {
        _offsets[index] = id.size() + 1; // 1 is added due to the implicitt accounting for ':'
    }
    _offsets[maxComponents] = id.size() + 1; // 1 is added due to the implicitt accounting for ':'
}

IdString::IdString(uint32_t maxComponents, uint32_t namespaceOffset, stringref rawId) :
    _offsets(maxComponents, namespaceOffset, rawId),
    _rawId(rawId)
{
}

void
IdString::validate() const
{
    if (_offsets.numComponents() < 2) {
        reportError("namespace");
    }
}

void
IdIdString::validate() const
{
    IdString::validate();
    if (getNumComponents() < 3) {
        reportError("document type");
    }
    if (getNumComponents() < 4) {
        reportError("key/value-pairs");
    }
}

IdString::UP
IdString::createIdString(const char * id, size_t sz_)
{
    if (sz_ > 4) {
        if (_G_doc.as32 == *reinterpret_cast<const uint32_t *>(id)) {
            return IdString::UP(new DocIdString(stringref(id, sz_)));
        } else if ((sz_ == 6) && (_G_null.as32 == *reinterpret_cast<const uint32_t *>(id)) && (id[4] == ':') && (id[5] == ':')) {
            return IdString::UP(new NullIdString());
        } else if (_G_id.as16 == *reinterpret_cast<const uint16_t *>(id) && id[2] == ':') {
            return IdString::UP(new IdIdString(stringref(id, sz_)));
        } else if (sz_ > 8) {
            if (_G_userdoc.as64 == *reinterpret_cast<const uint64_t *>(id)) {
                return IdString::UP(new UserDocIdString(stringref(id, sz_)));
            } else if (_G_groupdoc.as64 == *reinterpret_cast<const uint64_t *>(id) && (id[8] == ':')) {
                return IdString::UP(new GroupDocIdString(stringref(id, sz_)));
            } else {
                reportNoSchemeSeparator(id);
            }
        } else {
            reportTooShortDocId(id, 8);
        }
    } else {
        reportTooShortDocId(id, 5);
    }
    return IdString::UP();
}

namespace {
union LocationUnion {
    uint8_t _key[16];
    IdString::LocationType _location[2];
};

IdString::LocationType makeLocation(stringref s) {
    LocationUnion location;
    fastc_md5sum(reinterpret_cast<const unsigned char*>(s.data()), s.size(), location._key);
    return location._location[0];
}

uint64_t parseNumber(stringref number) {
    char* errPos = NULL;
    errno = 0;
    uint64_t n = strtoul(number.data(), &errPos, 10);
    if (*errPos) {
        throw IdParseException(
                "'n'-value must be a 64-bit number. It was " +
                number, VESPA_STRLOC);
    }
    if (errno == ERANGE) {
        throw IdParseException("'n'-value out of range "
                               "(" + number + ")", VESPA_STRLOC);
    }
    return n;
}

void setLocation(IdString::LocationType &loc, IdString::LocationType val,
                 bool &has_set_location, stringref key_values) {
    if (has_set_location) {
        throw IdParseException("Illegal key combination in "
                               + key_values);
    }
    loc = val;
    has_set_location = true;
}


}  // namespace

IdIdString::IdIdString(stringref id)
    : IdString(4, 3, id),
      _location(0),
      _groupOffset(0),
      _has_number(false)
{
    // TODO(magnarn): Require that keys are lexicographically ordered.
    validate();

    stringref key_values(getComponent(2));
    char key(0);
    string::size_type pos = 0;
    bool has_set_location = false;
    bool hasFoundKey(false);
    for (string::size_type i = 0; i < key_values.size(); ++i) {
        if (!hasFoundKey && (key_values[i] == '=')) {
            key = key_values[i-1];
            pos = i + 1;
            hasFoundKey = true;
        } else if (key_values[i] == ',' || i == key_values.size() - 1) {
            stringref value(key_values.substr(pos, i - pos + (i == key_values.size() - 1)));
            if (key == 'n') {
                char tmp=value[value.size()];
                const_cast<char &>(value[value.size()]) = 0;
                setLocation(_location, parseNumber(value), has_set_location, key_values);
                _has_number = true;
                const_cast<char &>(value[value.size()]) = tmp;
            } else if (key == 'g') {
                setLocation(_location, makeLocation(value), has_set_location, key_values);
                _groupOffset = offset(2) + pos;
            } else {
                throw IdParseException(make_string("Illegal key '%c'", key));
            }
            pos = i + 1;
            hasFoundKey = false;
        }
    }

    if (!has_set_location) {
        _location = makeLocation(getNamespaceSpecific());
    }
}

IdString::LocationType
DocIdString::getLocation() const
{
    return makeLocation(toString());
}

IdString::LocationType
GroupDocIdString::getLocation() const
{
    return makeLocation(getGroup());
}

DocIdString::DocIdString(stringref ns, stringref id) :
    IdString(2, 4, "doc:" + ns + ":" + id)
{
    validate();
}

DocIdString::DocIdString(stringref rawId) :
    IdString(2, 4, rawId)
{
    validate();
}

UserDocIdString::UserDocIdString(stringref rawId) :
    IdString(3, 8, rawId),
    _userId(getAsNumber(rawId.substr(offset(1), offset(2) - offset(1) - 1), "userid"))
{
    validate();
}

GroupDocIdString::GroupDocIdString(stringref rawId) :
    IdString(3, 9, rawId)
{
    validate();
}

IdString::LocationType
GroupDocIdString::locationFromGroupName(vespalib::stringref name)
{
    return makeLocation(name);
}

} // document
