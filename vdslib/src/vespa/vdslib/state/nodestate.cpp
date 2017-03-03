// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "nodestate.h"

#include <boost/lexical_cast.hpp>
#include <vespa/vespalib/text/stringtokenizer.h>
#include <vespa/document/util/stringutil.h>
#include <vespa/vespalib/util/exceptions.h>
#include <vespa/vespalib/stllike/asciistream.h>
#include <vespa/vdslib/state/random.h>
#include <sstream>
#include <cmath>
#include <vespa/log/log.h>

LOG_SETUP(".vdslib.nodestate");

namespace storage {
namespace lib {

NodeState::NodeState(const NodeState &) = default;
NodeState & NodeState::operator = (const NodeState &) = default;
NodeState::NodeState(NodeState &&) = default;
NodeState & NodeState::operator = (NodeState &&) = default;
NodeState::~NodeState() { }

NodeState::NodeState()
    : _type(0),
      _state(0),
      _description(""),
      _capacity(1.0),
      _reliability(1),
      _initProgress(0.0),
      _minUsedBits(16),
      _diskStates(),
      _anyDiskDown(false),
      _startTimestamp(0)
{
    setState(State::UP);
}

NodeState::NodeState(const NodeType& type, const State& state,
                     const vespalib::stringref & description,
                     double capacity, uint16_t reliability)
    : _type(&type),
      _state(0),
      _description(description),
      _capacity(1.0),
      _reliability(1),
      _initProgress(0.0),
      _minUsedBits(16),
      _diskStates(),
      _anyDiskDown(false),
      _startTimestamp(0)
{
    setState(state);
    if (type == NodeType::STORAGE) {
        setCapacity(capacity);
        setReliability(reliability);
    }
}

namespace {
    struct DiskData {
        bool empty;
        uint16_t diskIndex;
        std::ostringstream ost;

        DiskData() : empty(true), diskIndex(0), ost() {}

        void addTo(std::vector<DiskState>& diskStates) {
            if (!empty) {
                while (diskIndex >= diskStates.size()) {
                    diskStates.push_back(DiskState(State::UP));
                }
                diskStates[diskIndex] = DiskState(ost.str());
                empty = true;
                ost.str("");
            }
        }
    };
}

NodeState::NodeState(const vespalib::stringref & serialized, const NodeType* type)
    : _type(type),
      _state(&State::UP),
      _description(),
      _capacity(1.0),
      _reliability(1),
      _initProgress(0.0),
      _minUsedBits(16),
      _diskStates(),
      _anyDiskDown(false),
      _startTimestamp(0)
{

    vespalib::StringTokenizer st(serialized, " \t\f\r\n");
    st.removeEmptyTokens();
    DiskData diskData;
    for (vespalib::StringTokenizer::Iterator it = st.begin();
         it != st.end(); ++it)
    {
        std::string::size_type index = it->find(':');
        if (index == std::string::npos) {
            throw vespalib::IllegalArgumentException(
                    "Token " + *it + " does not contain ':': " + serialized,
                    VESPA_STRLOC);
        }
        std::string key = it->substr(0, index);
        std::string value = it->substr(index + 1);
        if (key.size() > 0) switch (key[0]) {
            case 'b':
                if (_type != 0 && *type != NodeType::STORAGE) break;
                if (key.size() > 1) break;
                try{
                    setMinUsedBits(boost::lexical_cast<uint32_t>(value));
                } catch (...) {
                    throw vespalib::IllegalArgumentException(
                            "Illegal used bits '" + value + "'. Used bits "
                            "must be a positive integer ",
                            VESPA_STRLOC);
                }
                continue;
            case 's':
                if (key.size() > 1) break;
                setState(State::get(value));
                continue;
            case 'c':
                if (key.size() > 1) break;
                if (_type != 0 && *type != NodeType::STORAGE) break;
                try{
                    setCapacity(boost::lexical_cast<double>(value));
                } catch (...) {
                    throw vespalib::IllegalArgumentException(
                            "Illegal capacity '" + value + "'. Capacity must be"
                            "a positive floating point number", VESPA_STRLOC);
                }
                continue;
            case 'r':
                if (_type != 0 && *type != NodeType::STORAGE) break;
                if (key.size() > 1) break;
                try{
                    setReliability(boost::lexical_cast<uint16_t>(value));
                } catch (...) {
                    throw vespalib::IllegalArgumentException(
                            "Illegal reliability '" + value + "'. Reliability "
                            "must be a positive integer",
                            VESPA_STRLOC);
                }
                continue;
            case 'i':
                if (key.size() > 1) break;
                try{
                    setInitProgress(boost::lexical_cast<double>(value));
                } catch (...) {
                    throw vespalib::IllegalArgumentException(
                            "Illegal init progress '" + value + "'. Init "
                            "progress must be a floating point number from 0.0 "
                            "to 1.0",
                            VESPA_STRLOC);
                }
                continue;
            case 't':
                if (key.size() > 1) break;
                try{
                    setStartTimestamp(boost::lexical_cast<uint64_t>(value));
                } catch (...) {
                    throw vespalib::IllegalArgumentException(
                            "Illegal start timestamp '" + value + "'. Start "
                            "timestamp must be 0 or positive long.",
                            VESPA_STRLOC);
                }
                continue;
            case 'm':
                if (key.size() > 1) break;
                _description = document::StringUtil::unescape(value);
                continue;
            case 'd':
            {
                if (_type != 0 && *type != NodeType::STORAGE) break;
                if (key.size() == 1) {
                    uint16_t size(0);
                    try{
                        size = boost::lexical_cast<uint16_t>(value);
                    } catch (...) {
                        throw vespalib::IllegalArgumentException(
                            "Invalid disk count '" + value + "'. Need a "
                            "positive integer value", VESPA_STRLOC);
                    }
                    while (_diskStates.size() < size) {
                        _diskStates.push_back(DiskState(State::UP));
                    }
                    continue;
                }
                if (key[1] != '.') break;
                uint16_t diskIndex;
                std::string::size_type endp = key.find('.', 2);
                std::string indexStr;
                if (endp == std::string::npos) {
                    indexStr = key.substr(2);
                } else {
                    indexStr = key.substr(2, endp - 2);
                }
                try{
                    diskIndex = boost::lexical_cast<uint16_t>(indexStr);
                } catch (...) {
                    throw vespalib::IllegalArgumentException(
                        "Invalid disk index '" + indexStr + "'. Need a "
                        "positive integer value", VESPA_STRLOC);
                }
                if (diskIndex >= _diskStates.size()) {
                    std::ostringstream ost;
                    ost << "Cannot index disk " << diskIndex << " of "
                        << _diskStates.size();
                    throw vespalib::IllegalArgumentException(
                            ost.str(), VESPA_STRLOC);
                }
                if (diskData.diskIndex != diskIndex) {
                    diskData.addTo(_diskStates);
                }
                if (endp == std::string::npos) {
                    diskData.ost << " s:" << value;
                } else {
                    diskData.ost << " " << key.substr(endp + 1) << ':' << value;
                }
                diskData.diskIndex = diskIndex;
                diskData.empty = false;
                continue;
            }
            default:
                break;
        }
        LOG(debug, "Unknown key %s in nodestate. Ignoring it, assuming it's a "
                   "new feature from a newer version than ourself: %s",
            key.c_str(), serialized.c_str());
    }
    diskData.addTo(_diskStates);
    updateAnyDiskDownFlag();
}

void
NodeState::updateAnyDiskDownFlag() {
    bool anyDown = false;
    for (uint32_t i=0; i<_diskStates.size(); ++i) {
        if (_diskStates[i].getState() != State::UP) {
            anyDown = true;
        }
    }
    _anyDiskDown = anyDown;
}

namespace {
    struct SeparatorPrinter {
        mutable bool first;
        SeparatorPrinter() : first(true) {}

        void print(vespalib::asciistream & os) const {
            if (first) {
                first = false;
            } else {
                os << ' ';
            }
        }
    };

    vespalib::asciistream & operator<<(vespalib::asciistream & os, const SeparatorPrinter& sep)
    {
        sep.print(os);
        return os;
    }

}

void
NodeState::serialize(vespalib::asciistream & out, const vespalib::stringref & prefix,
                     bool includeDescription, bool includeDiskDescription,
                     bool useOldFormat) const
{
    SeparatorPrinter sep;
        // Always give node state if not part of a system state
        // to prevent empty serialization
    if (*_state != State::UP || prefix.size() == 0) {
        out << sep << prefix << "s:";
        if (useOldFormat && *_state == State::STOPPING) {
            out << State::DOWN.serialize();
        } else {
            out << _state->serialize();
        }
    }
    if (_capacity != 1.0) {
        out << sep << prefix << "c:" << _capacity;
    }
    if (_reliability != 1) {
        out << sep << prefix << "r:" << _reliability;
    }
    if (_minUsedBits != 16) {
        out << sep << prefix << "b:" << _minUsedBits;
    }
    if (*_state == State::INITIALIZING && !useOldFormat) {
        out << sep << prefix << "i:" << _initProgress;
    }
    if (_startTimestamp != 0) {
        out << sep << prefix << "t:" << _startTimestamp;
    }
    if (_diskStates.size() > 0) {
        out << sep << prefix << "d:" << _diskStates.size();
        for (uint16_t i = 0; i < _diskStates.size(); ++i) {
            vespalib::asciistream diskPrefix;
            diskPrefix << prefix << "d." << i << ".";
            vespalib::asciistream disk;
            _diskStates[i].serialize(disk, diskPrefix.str(),
                                     includeDiskDescription, useOldFormat);
            if ( ! disk.str().empty()) {
                out << " " << disk.str();
            }
        }
    }
    if (includeDescription && ! _description.empty()) {
        out << sep << prefix << "m:"
            << document::StringUtil::escape(_description, ' ');
    }
}

const DiskState&
NodeState::getDiskState(uint16_t index) const
{
    static const DiskState defaultState(State::UP);
    if (_diskStates.size() == 0) return defaultState;
    if (index >= _diskStates.size()) {
        std::ostringstream ost;
        ost << "Cannot get status of disk " << index << " of "
            << _diskStates.size() << ".";
        throw vespalib::IllegalArgumentException(ost.str(), VESPA_STRLOC);
    }
    return _diskStates[index];
}

void
NodeState::setState(const State& state)
{
    if (_type != 0) {
            // We don't know whether you want to store reported, wanted or
            // current node state, so we must accept any.
        if (!state.validReportedNodeState(*_type)
            && !state.validWantedNodeState(*_type))
        {
            throw vespalib::IllegalArgumentException(
                    state.toString(true) + " is not a legal "
                    + _type->toString() + " state", VESPA_STRLOC);
        }
    }
    _state = &state;
}

void
NodeState::setMinUsedBits(uint32_t usedBits) {
    if (usedBits < 1 || usedBits > 58) {
        std::ostringstream ost;
        ost << "Illegal used bits '" << usedBits << "'. Minimum used bits"
                "must be an integer > 0 and < 59.";
        throw vespalib::IllegalArgumentException(ost.str(), VESPA_STRLOC);
    }

    _minUsedBits = usedBits;
}

void
NodeState::setCapacity(vespalib::Double capacity)
{
    if (capacity < 0) {
        std::ostringstream ost;
        ost << "Illegal capacity '" << capacity << "'. Capacity "
                "must be a positive floating point number";
        throw vespalib::IllegalArgumentException(ost.str(), VESPA_STRLOC);
    }
    if (_type != 0 && *_type != NodeType::STORAGE) {
        throw vespalib::IllegalArgumentException(
                "Capacity only make sense for storage nodes.", VESPA_STRLOC);
    }
    _capacity = capacity;
}

void
NodeState::setReliability(uint16_t reliability)
{
    if (reliability == 0) {
        std::ostringstream ost;
        ost << "Illegal reliability '" << reliability << "'. Reliability "
                "must be a positive integer.";
        throw vespalib::IllegalArgumentException(ost.str(), VESPA_STRLOC);
    }
    if (_type != 0 && *_type != NodeType::STORAGE) {
        throw vespalib::IllegalArgumentException(
                "Reliability only make sense for storage nodes.", VESPA_STRLOC);
    }
    _reliability = reliability;
}

void
NodeState::setInitProgress(vespalib::Double initProgress)
{
    if (initProgress < 0 || initProgress > 1.0) {
        std::ostringstream ost;
        ost << "Illegal init progress '" << initProgress << "'. Init progress "
               "must be a floating point number from 0.0 to 1.0";
        throw vespalib::IllegalArgumentException(ost.str(), VESPA_STRLOC);
    }
    _initProgress = initProgress;
}

void
NodeState::setStartTimestamp(uint64_t startTimestamp)
{
    _startTimestamp = startTimestamp;
}

void
NodeState::setDiskCount(uint16_t count)
{
    while (_diskStates.size() > count) {
        _diskStates.pop_back();
    }
    _diskStates.reserve(count);
    while (_diskStates.size() < count) {
        _diskStates.push_back(DiskState(State::UP));
    }
    updateAnyDiskDownFlag();
}

void
NodeState::setDiskState(uint16_t index, const DiskState& state)
{
    if (index >= _diskStates.size()) {
        throw vespalib::IllegalArgumentException(
                vespalib::make_string("Can't set state of disk %u of %u.",
                                      index, (uint32_t) _diskStates.size()),
                VESPA_STRLOC);
    }
    _diskStates[index] = state;
    updateAnyDiskDownFlag();
}

void
NodeState::print(std::ostream& out, bool verbose,
                 const std::string& indent) const
{
    if (!verbose) {
        vespalib::asciistream tmp;
        serialize(tmp);
        out << tmp.str();
        return;
    }
    _state->print(out, verbose, indent);
    if (_capacity != 1.0) {
        out << ", capacity " << _capacity;
    }
    if (_reliability != 1) {
        out << ", reliability " << _reliability;
    }
    if (_minUsedBits != 16) {
        out << ", minimum used bits " << _minUsedBits;
    }
    if (*_state == State::INITIALIZING) {
        out << ", init progress " << _initProgress;
    }
    if (_startTimestamp != 0) {
        out << ", start timestamp " << _startTimestamp;
    }
    if (_diskStates.size() > 0) {
        bool printedHeader = false;
        for (uint32_t i=0; i<_diskStates.size(); ++i) {
            if (_diskStates[i] != DiskState(State::UP)) {
                if (!printedHeader) {
                    out << ",";
                    printedHeader = true;
                }
                out << " Disk " << i << "(";
                _diskStates[i].print(out, false, indent);
                out << ")";
            }
        }
    }
    if (_description.size() > 0) {
        out << ": " << _description;
    }
}

bool
NodeState::operator==(const NodeState& other) const
{
    if (_state != other._state ||
        _capacity != other._capacity ||
        _reliability != other._reliability ||
        _minUsedBits != other._minUsedBits ||
        _startTimestamp != other._startTimestamp ||
        (*_state == State::INITIALIZING
            && _initProgress != other._initProgress))
    {
        return false;
    }
    for (uint32_t i=0, n=std::max(_diskStates.size(), other._diskStates.size());
         i < n; ++i)
    {
        if (getDiskState(i) != other.getDiskState(i)) {
            return false;
        }
    }
    return true;
}

bool
NodeState::similarTo(const NodeState& other) const
{
    if (_state != other._state ||
        _capacity != other._capacity ||
        _reliability != other._reliability ||
        _minUsedBits != other._minUsedBits ||
        _startTimestamp < other._startTimestamp)
    {
        return false;
    }
    if (*_state == State::INITIALIZING) {
        double limit = getListingBucketsInitProgressLimit();
        bool below1 = (_initProgress < limit);
        bool below2 = (other._initProgress < limit);
        if (below1 != below2) {
            return false;
        }
    }
    for (uint32_t i=0, n=std::max(_diskStates.size(), other._diskStates.size());
         i < n; ++i)
    {
        if (getDiskState(i) != other.getDiskState(i)) {
            return false;
        }
    }
    return true;
}

void
NodeState::verifySupportForNodeType(const NodeType& type) const
{
    if (_type != 0 && *_type == type) return;
    if (!_state->validReportedNodeState(type)
        && !_state->validWantedNodeState(type))
    {
        throw vespalib::IllegalArgumentException("State " + _state->toString()
                + " does not fit a node of type " + type.toString(),
                VESPA_STRLOC);
    }
    if (type == NodeType::DISTRIBUTOR && _capacity != 1.0) {
        throw vespalib::IllegalArgumentException("Capacity should not be "
                "set for a distributor node.", VESPA_STRLOC);
    }
    if (type == NodeType::DISTRIBUTOR && _reliability != 1) {
        throw vespalib::IllegalArgumentException("Reliability should not be "
                "set for a distributor node.", VESPA_STRLOC);
    }
}

std::string
NodeState::getTextualDifference(const NodeState& other) const {
    std::ostringstream source;
    std::ostringstream target;

    if (_state != other._state) {
        source << ", " << *_state;
        target << ", " << *other._state;
    }
    if (_capacity != other._capacity) {
        source << ", capacity " << _capacity;
        target << ", capacity " << other._capacity;
    }
    if (_reliability != other._reliability) {
        source << ", reliability " << _reliability;
        target << ", reliability " << other._reliability;
    }
    if (_minUsedBits != other._minUsedBits) {
        source << ", minUsedBits " << _minUsedBits;
        target << ", minUsedBits " << _minUsedBits;
    }
    if (_initProgress != other._initProgress) {
        if (_state == &State::INITIALIZING) {
            source << ", init progress " << _initProgress;
        }
        if (other._state == &State::INITIALIZING) {
            target << ", init progress " << other._initProgress;
        }
    }
    if (_startTimestamp != other._startTimestamp) {
        source << ", start timestamp " << _startTimestamp;
        target << ", start timestamp " << other._startTimestamp;
    }

    if (_diskStates.size() != other._diskStates.size()) {
        source << ", " << _diskStates.size() << " disks";
        target << ", " << other._diskStates.size() << " disks";
    } else {
        for (uint32_t i=0; i<_diskStates.size(); ++i) {
            if (_diskStates[i] != other._diskStates[i]) {
                source << ", disk " << i << _diskStates[i];
                target << ", disk " << i << other._diskStates[i];
            }
        }
    }

    if (source.str().length() < 2 || target.str().length() < 2) {
        return "no change";
    }

    std::ostringstream total;
    total << source.str().substr(2) << " to " << target.str().substr(2);
    if (other._description != _description) {
        total << " (" << other._description << ")";
    }
    return total.str();
}

} // lib
} // storage
