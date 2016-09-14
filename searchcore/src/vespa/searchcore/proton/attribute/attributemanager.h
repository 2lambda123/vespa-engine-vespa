// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "attribute_collection_spec.h"
#include "i_attribute_factory.h"
#include "i_attribute_manager.h"
#include "i_attribute_initializer_registry.h"
#include <set>
#include <vespa/searchlib/common/tunefileinfo.h>
#include <vespa/searchcore/proton/attribute/flushableattribute.h>
#include <vespa/searchlib/attribute/attributevector.h>
#include <vespa/searchcommon/common/schema.h>

namespace search
{

namespace common
{

class FileHeaderContext;

}

}

namespace proton
{


/**
 * Specialized attribute manager for proton.
 */
class AttributeManager : public proton::IAttributeManager
{
private:
    typedef search::attribute::Config Config;
    typedef search::SerialNum SerialNum;
    typedef AttributeCollectionSpec Spec;

    class AttributeWrap : public search::AttributeVector::SP
    {
    private:
        bool _isExtra;
    public:
        AttributeWrap() : search::AttributeVector::SP(), _isExtra(false) { }
        AttributeWrap(const search::AttributeVector::SP & a, bool isExtra_ = false) : 
            search::AttributeVector::SP(a),
            _isExtra(isExtra_)
        { }
        bool isExtra() const { return _isExtra; }
    };

    typedef vespalib::hash_map<vespalib::string, AttributeWrap> AttributeMap;
    typedef vespalib::hash_map<vespalib::string, FlushableAttribute::SP> FlushableMap;

    AttributeMap _attributes;
    FlushableMap _flushables;
    std::vector<search::AttributeVector *> _writableAttributes;
    vespalib::string _baseDir;
    vespalib::string _documentSubDbName;
    const search::TuneFileAttributes _tuneFileAttributes;
    const search::common::FileHeaderContext &_fileHeaderContext;
    IAttributeFactory::SP _factory;
    std::shared_ptr<search::attribute::Interlock> _interlock;
    search::ISequencedTaskExecutor &_attributeFieldWriter;

    search::AttributeVector::SP internalAddAttribute(const vespalib::string &name,
                                                     const Config &cfg,
                                                     uint64_t serialNum,
                                                     const IAttributeFactory &factory);

    void addAttribute(const AttributeWrap &attribute);

    search::AttributeVector::SP findAttribute(const vespalib::string &name) const;

    FlushableAttribute::SP findFlushable(const vespalib::string &name) const;

    void createBaseDir();


    void transferExistingAttributes(const AttributeManager &currMgr,
                                    const Spec &newSpec,
                                    Spec::AttributeList &toBeAdded);

    void flushRemovedAttributes(const AttributeManager &currMgr,
                                const Spec &newSpec);

    void addNewAttributes(const Spec &newSpec,
                          const Spec::AttributeList &toBeAdded,
                          IAttributeInitializerRegistry &initializerRegistry);

    void transferExtraAttributes(const AttributeManager &currMgr);

public:
    typedef std::shared_ptr<AttributeManager> SP;

    AttributeManager(const vespalib::string &baseDir,
                     const vespalib::string &documentSubDbName,
                     const search::TuneFileAttributes &tuneFileAttributes,
                     const search::common::FileHeaderContext &
                     fileHeaderContext,
                     search::ISequencedTaskExecutor &attributeFieldWriter);

    AttributeManager(const vespalib::string &baseDir,
                     const vespalib::string &documentSubDbName,
                     const search::TuneFileAttributes &tuneFileAttributes,
                     const search::common::FileHeaderContext &
                     fileHeaderContext,
                     search::ISequencedTaskExecutor &attributeFieldWriter,
                     const IAttributeFactory::SP &factory);

    AttributeManager(const AttributeManager &currMgr,
                     const Spec &newSpec,
                     IAttributeInitializerRegistry &initializerRegistry);

    search::AttributeVector::SP addAttribute(const vespalib::string &name,
                                             const Config &cfg,
                                             uint64_t serialNum);

    void addInitializedAttributes(const std::vector<search::AttributeVector::SP> &attributes);

    void addExtraAttribute(const search::AttributeVector::SP &attribute);

    void flushAll(SerialNum currentSerial);

    FlushableAttribute::SP getFlushable(const vespalib::string &name);

    size_t getNumDocs() const;

    static void padAttribute(search::AttributeVector &v, uint32_t docIdLimit);

    // Implements search::IAttributeManager
    virtual search::AttributeGuard::UP getAttribute(const vespalib::string &name) const;

    virtual search::AttributeGuard::UP getAttributeStableEnum(const vespalib::string &name) const;

    /**
     * Fills all regular registered attributes (not extra attributes)
     * into the given list.
     */
    virtual void getAttributeList(std::vector<search::AttributeGuard> &list) const;

    virtual search::attribute::IAttributeContext::UP createContext() const;


    // Implements proton::IAttributeManager

    virtual proton::IAttributeManager::SP create(const Spec &spec) const;

    virtual std::vector<IFlushTarget::SP> getFlushTargets() const;

    virtual search::SerialNum getFlushedSerialNum(const vespalib::string &name) const;

    virtual SerialNum getOldestFlushedSerialNumber() const;

    virtual search::SerialNum
    getNewestFlushedSerialNumber() const;

    virtual void getAttributeListAll(std::vector<search::AttributeGuard> &list) const;

    virtual void wipeHistory(const search::index::Schema &historySchema);

    virtual const IAttributeFactory::SP &getFactory() const { return _factory; }

    virtual search::ISequencedTaskExecutor &
    getAttributeFieldWriter() const override;

    virtual search::AttributeVector *
    getWritableAttribute(const vespalib::string &name) const override;

    virtual const std::vector<search::AttributeVector *> &
    getWritableAttributes() const override;

    virtual void
    asyncForEachAttribute(std::shared_ptr<IAttributeFunctor> func) const override;

    virtual ExclusiveAttributeReadAccessor::UP
    getExclusiveReadAccessor(const vespalib::string &name) const override;
};

} // namespace proton

