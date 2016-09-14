// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".proton.attribute.attributemanager");
#include "attribute_factory.h"
#include "attribute_initializer.h"
#include "attributedisklayout.h"
#include "attributemanager.h"
#include "flushableattribute.h"
#include "sequential_attributes_initializer.h"
#include <vespa/searchlib/attribute/attributecontext.h>
#include <vespa/vespalib/io/fileutil.h>
#include <vespa/vespalib/data/fileheader.h>
#include <vespa/searchlib/attribute/interlock.h>
#include <vespa/searchlib/common/isequencedtaskexecutor.h>
#include <memory>
#include "i_attribute_functor.h"

using search::AttributeContext;
using search::AttributeEnumGuard;
using search::AttributeGuard;
using search::AttributeVector;
using search::IndexMetaInfo;
using search::TuneFileAttributes;
using search::attribute::IAttributeContext;
using search::index::Schema;
using search::common::FileHeaderContext;

namespace proton {

AttributeVector::SP
AttributeManager::internalAddAttribute(const vespalib::string &name,
                                       const Config &cfg,
                                       uint64_t serialNum,
                                       const IAttributeFactory &factory)
{
    AttributeInitializer initializer(_baseDir, _documentSubDbName, name, cfg, serialNum, factory);
    AttributeVector::SP attr = initializer.init();
    if (attr.get() != NULL) {
        attr->setInterlock(_interlock);
        addAttribute(attr);
    }
    return attr;
}

void
AttributeManager::addAttribute(const AttributeWrap &attribute)
{
    LOG(debug,
        "Adding attribute vector '%s'",
        attribute->getBaseFileName().c_str());
    _attributes[attribute->getName()] = attribute;
    assert(attribute->getInterlock() == _interlock);
    if ( ! attribute.isExtra() ) {
        // Flushing of extra attributes is handled elsewhere
        _flushables[attribute->getName()] = FlushableAttribute::SP
                (new FlushableAttribute(attribute, _baseDir,
                                        _tuneFileAttributes,
                                        _fileHeaderContext,
                                        _attributeFieldWriter));
        _writableAttributes.push_back(attribute.get());
    }
}

AttributeVector::SP
AttributeManager::findAttribute(const vespalib::string &name) const
{
    AttributeMap::const_iterator itr = _attributes.find(name);
    return (itr != _attributes.end())
        ? static_cast<const AttributeVector::SP &>(itr->second)
        : AttributeVector::SP();
}

FlushableAttribute::SP
AttributeManager::findFlushable(const vespalib::string &name) const
{
    FlushableMap::const_iterator itr = _flushables.find(name);
    return (itr != _flushables.end()) ? itr->second : FlushableAttribute::SP();
}

void
AttributeManager::createBaseDir()
{
    vespalib::mkdir(_baseDir, false);
}

void
AttributeManager::transferExistingAttributes(const AttributeManager &currMgr,
                                             const Spec &newSpec,
                                             Spec::AttributeList &toBeAdded)
{
    for (const auto &aspec : newSpec.getAttributes()) {
        AttributeVector::SP av = currMgr.findAttribute(aspec.getName());
        if (av.get() != NULL) { // transfer attribute
            LOG(debug,
                "Transferring attribute vector '%s' with %u docs and "
                "serial number %" PRIu64 " from current manager",
                av->getName().c_str(),
                av->getNumDocs(),
                av->getStatus().getLastSyncToken());
            addAttribute(av);
        } else {
            toBeAdded.push_back(aspec);
        }
    }
}

void
AttributeManager::flushRemovedAttributes(const AttributeManager &currMgr,
                                         const Spec &newSpec)
{
    for (const auto &kv : currMgr._attributes) {
        if (!newSpec.hasAttribute(kv.first) &&
            !kv.second.isExtra() &&
            kv.second->getStatus().getLastSyncToken() <
            newSpec.getCurrentSerialNum()) {
            FlushableAttribute::SP flushable =
                currMgr.findFlushable(kv.first);
            vespalib::Executor::Task::UP flushTask =
                flushable->initFlush(newSpec.getCurrentSerialNum());
            if (flushTask.get() != NULL) {
                LOG(debug,
                    "Flushing removed attribute vector '%s' with %u docs "
                    "and serial number %" PRIu64,
                    kv.first.c_str(),
                    kv.second->getNumDocs(),
                    kv.second->getStatus().getLastSyncToken());
                flushTask->run();
            }
        }
    }
}

void
AttributeManager::addNewAttributes(const Spec &newSpec,
                                   const Spec::AttributeList &toBeAdded,
                                   IAttributeInitializerRegistry &initializerRegistry)
{
    for (const auto &aspec : toBeAdded) {
        LOG(debug, "Creating initializer for attribute vector '%s': docIdLimit=%u, serialNumber=%" PRIu64,
            aspec.getName().c_str(),
            newSpec.getDocIdLimit(),
            newSpec.getCurrentSerialNum());

        AttributeInitializer::UP initializer =
                std::make_unique<AttributeInitializer>(_baseDir, _documentSubDbName, aspec.getName(),
                        aspec.getConfig(), newSpec.getCurrentSerialNum(), *_factory);
        initializerRegistry.add(std::move(initializer));

        // TODO: Might want to use hardlinks to make attribute vector
        // appear to have been flushed at resurrect time, eliminating
        // flushDone serials going backwards in document db, and allowing
        // for pruning of transaction log up to the resurrect serial
        // without having to reflush the resurrected attribute vector.

        // XXX: Need to wash attribute at resurrection time to get rid of
        // ghost values (lid freed and not reused), foreign values
        // (lid freed and reused by another document) and stale values
        // (lid still used by newer versions of the same document).
    }

}

void
AttributeManager::transferExtraAttributes(const AttributeManager &currMgr)
{
    for (const auto &kv : currMgr._attributes) {
        if (kv.second.isExtra()) {
            addAttribute(kv.second);
        }
    }
}

AttributeManager::AttributeManager(const vespalib::string &baseDir,
                                   const vespalib::string &documentSubDbName,
                                   const TuneFileAttributes &tuneFileAttributes,
                                   const FileHeaderContext &fileHeaderContext,
                                   search::ISequencedTaskExecutor &
                                   attributeFieldWriter)
    : proton::IAttributeManager(),
      _attributes(),
      _flushables(),
      _writableAttributes(),
      _baseDir(baseDir),
      _documentSubDbName(documentSubDbName),
      _tuneFileAttributes(tuneFileAttributes),
      _fileHeaderContext(fileHeaderContext),
      _factory(new AttributeFactory()),
      _interlock(std::make_shared<search::attribute::Interlock>()),
      _attributeFieldWriter(attributeFieldWriter)
{
    createBaseDir();
}


AttributeManager::AttributeManager(const vespalib::string &baseDir,
                                   const vespalib::string &documentSubDbName,
                                   const search::TuneFileAttributes &tuneFileAttributes,
                                   const search::common::FileHeaderContext &fileHeaderContext,
                                   search::ISequencedTaskExecutor &
                                   attributeFieldWriter,
                                   const IAttributeFactory::SP &factory)
    : proton::IAttributeManager(),
      _attributes(),
      _flushables(),
      _writableAttributes(),
      _baseDir(baseDir),
      _documentSubDbName(documentSubDbName),
      _tuneFileAttributes(tuneFileAttributes),
      _fileHeaderContext(fileHeaderContext),
      _factory(factory),
      _interlock(std::make_shared<search::attribute::Interlock>()),
      _attributeFieldWriter(attributeFieldWriter)
{
    createBaseDir();
}

AttributeManager::AttributeManager(const AttributeManager &currMgr,
                                   const Spec &newSpec,
                                   IAttributeInitializerRegistry &initializerRegistry)
    : proton::IAttributeManager(),
      _attributes(),
      _flushables(),
      _writableAttributes(),
      _baseDir(currMgr._baseDir),
      _documentSubDbName(currMgr._documentSubDbName),
      _tuneFileAttributes(currMgr._tuneFileAttributes),
      _fileHeaderContext(currMgr._fileHeaderContext),
      _factory(currMgr._factory),
      _interlock(currMgr._interlock),
      _attributeFieldWriter(currMgr._attributeFieldWriter)
{
    Spec::AttributeList toBeAdded;
    transferExistingAttributes(currMgr, newSpec, toBeAdded);
    flushRemovedAttributes(currMgr, newSpec);
    addNewAttributes(newSpec, toBeAdded, initializerRegistry);
    transferExtraAttributes(currMgr);
}

AttributeVector::SP
AttributeManager::addAttribute(const vespalib::string &name,
                               const Config &cfg,
                               uint64_t serialNum)
{
    return internalAddAttribute(name, cfg, serialNum, *_factory);
}

void
AttributeManager::addInitializedAttributes(const std::vector<search::AttributeVector::SP> &attributes)
{
    for (const auto &attribute : attributes) {
        attribute->setInterlock(_interlock);
        addAttribute(attribute);
    }
}

void
AttributeManager::addExtraAttribute(const AttributeVector::SP &attribute)
{
    attribute->setInterlock(_interlock);
    addAttribute(AttributeWrap(attribute, true));
}

void
AttributeManager::flushAll(SerialNum currentSerial)
{
    for (const auto &kv : _flushables) {
        vespalib::Executor::Task::UP task;
        task = kv.second->initFlush(currentSerial);
        if (task.get() != NULL) {
            task->run();
        }
    }
}

FlushableAttribute::SP
AttributeManager::getFlushable(const vespalib::string &name)
{
    return findFlushable(name);
}

size_t
AttributeManager::getNumDocs() const
{
    return _attributes.empty()
        ? 0
        : _attributes.begin()->second->getNumDocs();
}

void
AttributeManager::padAttribute(AttributeVector &v, uint32_t docIdLimit)
{
    uint32_t needCommit = 0;
    uint32_t docId(v.getNumDocs());
    while (v.getNumDocs() < docIdLimit) {
        if (!v.addDoc(docId)) {
            throw vespalib::IllegalStateException
                (vespalib::make_string("Failed to pad doc %u/%u to "
                                       "attribute vector '%s'",
                                       docId,
                                       docIdLimit,
                                       v.getName().c_str()));
        }
        v.clearDoc(docId);
        if (++needCommit >= 1024) {
            needCommit = 0;
            v.commit();
        }
    }
    if (needCommit > 1)
        v.commit();
    assert(v.getNumDocs() >= docIdLimit);
}

AttributeGuard::UP
AttributeManager::getAttribute(const vespalib::string &name) const
{
    return AttributeGuard::UP(new AttributeGuard(findAttribute(name)));
}

AttributeGuard::UP
AttributeManager::getAttributeStableEnum(const vespalib::string &name) const
{
    return AttributeGuard::UP(new AttributeEnumGuard(findAttribute(name)));
}

void
AttributeManager::getAttributeList(std::vector<AttributeGuard> &list) const
{
    list.reserve(_attributes.size());
    for (const auto &kv : _attributes) {
        if (!kv.second.isExtra()) {
            list.push_back(AttributeGuard(kv.second));
        }
    }
}

IAttributeContext::UP
AttributeManager::createContext() const
{
    return IAttributeContext::UP(new AttributeContext(*this));
}

proton::IAttributeManager::SP
AttributeManager::create(const Spec &spec) const
{
    SequentialAttributesInitializer initializer(spec.getDocIdLimit());
    proton::AttributeManager::SP result = std::make_shared<AttributeManager>(*this, spec, initializer);
    result->addInitializedAttributes(initializer.getInitializedAttributes());
    return result;
}

std::vector<IFlushTarget::SP>
AttributeManager::getFlushTargets() const
{
    std::vector<IFlushTarget::SP> list;
    list.reserve(_flushables.size());
    for (const auto &kv : _flushables) {
        list.push_back(kv.second);
    }
    return list;
}

search::SerialNum
AttributeManager::getFlushedSerialNum(const vespalib::string &name) const
{
    FlushableAttribute::SP flushable = findFlushable(name);
    if (flushable.get() != nullptr) {
        return flushable->getFlushedSerialNum();
    }
    return 0;
}

search::SerialNum
AttributeManager::getOldestFlushedSerialNumber() const
{
    SerialNum num = -1;
    for (const auto &kv : _flushables) {
        num = std::min(num, kv.second->getFlushedSerialNum());
    }
    return num;
}

search::SerialNum
AttributeManager::getNewestFlushedSerialNumber() const
{
    SerialNum num = 0;
    for (const auto &kv : _flushables) {
        num = std::max(num, kv.second->getFlushedSerialNum());
    }
    return num;
}

void
AttributeManager::getAttributeListAll(std::vector<AttributeGuard> &list) const
{
    list.reserve(_attributes.size());
    for (const auto &kv : _attributes) {
        list.push_back(AttributeGuard(kv.second));
    }
}

void
AttributeManager::wipeHistory(const Schema &historySchema)
{
    for (uint32_t i = 0; i < historySchema.getNumAttributeFields(); ++i) {
        const Schema::AttributeField & field =
            historySchema.getAttributeField(i);
        AttributeDiskLayout::removeAttribute(_baseDir, field.getName());
    }
}

search::ISequencedTaskExecutor &
AttributeManager::getAttributeFieldWriter() const
{
    return _attributeFieldWriter;
}


AttributeVector *
AttributeManager::getWritableAttribute(const vespalib::string &name) const
{
    AttributeMap::const_iterator itr = _attributes.find(name);
    if (itr == _attributes.end() || itr->second.isExtra()) {
        return nullptr;
    }
    return itr->second.get();
}


const std::vector<AttributeVector *> &
AttributeManager::getWritableAttributes() const
{
    return _writableAttributes;
}


void
AttributeManager::asyncForEachAttribute(std::shared_ptr<IAttributeFunctor>
                                        func) const
{
    for (const auto &attr : _attributes) {
        if (attr.second.isExtra()) {
            continue;
        }
        AttributeVector::SP attrsp = attr.second;
        _attributeFieldWriter.
            execute(attr.first, [attrsp, func]() { (*func)(*attrsp); });
    }
}

ExclusiveAttributeReadAccessor::UP
AttributeManager::getExclusiveReadAccessor(const vespalib::string &name) const
{
    AttributeVector::SP attribute = findAttribute(name);
    if (attribute) {
        return std::make_unique<ExclusiveAttributeReadAccessor>(attribute, _attributeFieldWriter);
    }
    return ExclusiveAttributeReadAccessor::UP();
}

} // namespace proton
