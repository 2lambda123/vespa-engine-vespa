// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "fileconfigmanager.h"
#include "bootstrapconfig.h"
#include <vespa/config/print/fileconfigwriter.h>
#include <vespa/config/print/fileconfigsnapshotreader.h>
#include <vespa/config/print/fileconfigsnapshotwriter.h>
#include <vespa/searchcommon/common/schemaconfigurer.h>
#include <vespa/searchlib/index/schemautil.h>
#include <vespa/vespalib/io/fileutil.h>
#include <vespa/config-summarymap.h>
#include <vespa/config-rank-profiles.h>
#include <vespa/searchsummary/config/config-juniperrc.h>
#include <fstream>
#include <vespa/fastos/file.h>
#include <vespa/config/helper/configgetter.hpp>

#include <vespa/log/log.h>
LOG_SETUP(".proton.server.fileconfigmanager");

using document::DocumentTypeRepo;
using document::DocumenttypesConfig;
using search::IndexMetaInfo;
using search::SerialNum;
using search::index::Schema;
using cloud::config::filedistribution::FiledistributorrpcConfig;
using vespa::config::search::AttributesConfig;
using vespa::config::search::IndexschemaConfig;
using vespa::config::search::RankProfilesConfig;
using vespa::config::search::SummaryConfig;
using vespa::config::search::SummarymapConfig;
using vespa::config::search::core::ProtonConfig;
using vespa::config::search::summary::JuniperrcConfig;
using vespalib::nbostream;

typedef IndexMetaInfo::SnapshotList SnapshotList;

namespace proton {

namespace {

vespalib::string
makeSnapDirBaseName(SerialNum serialNum)
{
    std::ostringstream os;
    os << "config-" << serialNum;
    return os.str();
}

vespalib::string
makeExtraConfigsFileName(const vespalib::string &snapDir)
{
    return snapDir + "/extraconfigs.dat";
}


void
fsyncFile(const vespalib::string &fileName)
{
    FastOS_File f;
    f.OpenReadWrite(fileName.c_str());
    if (!f.IsOpened()) {
        LOG(error,
            "Could not open file '%s' for fsync",
            fileName.c_str());
        return;
    }
    if (!f.Sync()) {
        LOG(error,
            "Could not fsync file '%s'",
            fileName.c_str());
    }
    f.Close();
}

template <class Config>
void
saveHelper(const vespalib::string &snapDir,
           const vespalib::string &name,
           const Config &config)
{
    vespalib::string fileName(snapDir + "/" + name + ".cfg");
    config::FileConfigWriter writer(fileName);
    bool ok = writer.write(config);
    assert(ok);
    (void) ok;
    fsyncFile(fileName);
}

template <class Config>
void
save(const vespalib::string &snapDir,
     const Config &config)
{
    saveHelper(snapDir, config.defName(), config);
}

void writeExtraConfigs(const vespalib::string &snapDir,
                       const DocumentDBConfig &snapshot)
{
    vespalib::string extraName(makeExtraConfigsFileName(snapDir));
    config::FileConfigSnapshotWriter writer(extraName);
    bool extraConfigsWriterResult = writer.write(snapshot.getExtraConfigs());
    assert(extraConfigsWriterResult);
    (void) extraConfigsWriterResult;
    fsyncFile(extraName);
}

config::ConfigSnapshot
readExtraConfigs(const vespalib::string &snapDir)
{
    vespalib::string fileName = makeExtraConfigsFileName(snapDir);
    if (vespalib::fileExists(fileName)) {
        config::FileConfigSnapshotReader reader(fileName);
        return reader.read();
    } else {
        LOG(warning, "Did not find data file for extra configs '%s' during loading of config snapshot. "
            "Using empty extra configs set.", fileName.c_str());
    }
    return config::ConfigSnapshot();
}


class ConfigFile
{
    typedef std::shared_ptr<ConfigFile> SP;

    vespalib::string _name;
    time_t _modTime;
    std::vector<char> _content;

public:
    ConfigFile();
    ~ConfigFile();

    ConfigFile(const vespalib::string &name,
               const vespalib::string &fullName);

    nbostream &serialize(nbostream &stream) const;

    nbostream &deserialize(nbostream &stream);

    void save(const vespalib::string &snapDir) const;
};


ConfigFile::ConfigFile()
    : _name(),
      _modTime(0),
      _content()
{
}

ConfigFile::~ConfigFile() {}


ConfigFile::ConfigFile(const vespalib::string &name,
                       const vespalib::string &fullName)
    : _name(name),
      _modTime(0),
      _content()
{
    FastOS_File file;
    bool openRes = file.OpenReadOnlyExisting(false, fullName.c_str());
    if (!openRes)
        return;
    int64_t fileSize = file.getSize();
    _content.resize(fileSize);
    file.ReadBuf(&_content[0], fileSize);
    _modTime = file.GetModificationTime();
    file.Close();
}


nbostream &
ConfigFile::serialize(nbostream &stream) const
{
    assert(strchr(_name.c_str(), '/') == NULL);
    stream << _name;
    stream << _modTime;
    uint32_t sz = _content.size();
    stream << sz;
    stream.write(&_content[0], sz);
    return stream;
}


nbostream &
ConfigFile::deserialize(nbostream &stream)
{
    stream >> _name;
    assert(strchr(_name.c_str(), '/') == NULL);
    stream >> _modTime;
    uint32_t sz;
    stream >> sz;
    _content.resize(sz);
    assert(stream.size() >= sz);
    memcpy(&_content[0], stream.peek(), sz);
    stream.adjustReadPos(sz);
    return stream;
}


void
ConfigFile::save(const vespalib::string &snapDir) const
{
    vespalib::string fullName = snapDir + "/" + _name;
    FastOS_File file;
    bool openRes = file.OpenWriteOnlyTruncate(fullName.c_str());
    assert(openRes);
    (void) openRes;

    file.WriteBuf(&_content[0], _content.size());
    bool closeRes = file.Close();
    assert(closeRes);
    (void) closeRes;

    fsyncFile(fullName);
}


nbostream &
operator<<(nbostream &stream, const ConfigFile &configFile)
{
    return configFile.serialize(stream);
}


nbostream &
operator>>(nbostream &stream, ConfigFile &configFile)
{
    return configFile.deserialize(stream);
}


std::vector<vespalib::string>
getFileList(const vespalib::string &snapDir)
{
    std::vector<vespalib::string> res;
    FastOS_DirectoryScan dirScan(snapDir.c_str());
    while (dirScan.ReadNext()) {
        if (strcmp(dirScan.GetName(), ".") == 0 ||
            strcmp(dirScan.GetName(), "..") == 0)
            continue;
        res.push_back(dirScan.GetName());
    }
    std::sort(res.begin(), res.end());
    return res;
}


}

FileConfigManager::FileConfigManager(const vespalib::string &baseDir,
                                     const vespalib::string &configId,
                                     const vespalib::string &docTypeName)
    : _baseDir(baseDir),
      _configId(configId),
      _docTypeName(docTypeName),
      _info(baseDir),
      _protonConfig()
{
    vespalib::mkdir(baseDir, false);
    if (!_info.load())
        _info.save();
    removeInvalid();
    _protonConfig.reset(new ProtonConfig());
}


FileConfigManager::~FileConfigManager(void)
{
}


SerialNum
FileConfigManager::getBestSerialNum(void) const
{
    Snapshot snap = _info.getBestSnapshot();
    return snap.valid ? snap.syncToken : UINT64_C(0);
}


SerialNum
FileConfigManager::getOldestSerialNum(void) const
{
    SerialNum res = 0;
    const SnapshotList &snaps = _info.snapshots();
    for (const auto &snap : snaps) {
        if (!snap.valid || snap.syncToken == 0)
            continue;
        if (res == 0 || res > snap.syncToken)
            res = snap.syncToken;
    }
    return res;
}


void
FileConfigManager::saveConfig(const DocumentDBConfig &snapshot,
                              const search::index::Schema &historySchema,
                              SerialNum serialNum)
{
    if (getBestSerialNum() >= serialNum) {
        LOG(warning, "Config for serial >= %" PRIu64 " already saved",
            static_cast<uint64_t>(serialNum));
        return;
    }
    vespalib::string snapDirBaseName(makeSnapDirBaseName(serialNum));
    vespalib::string snapDir(_baseDir + "/" + snapDirBaseName);
    Snapshot snap(false, serialNum, snapDirBaseName);
    _info.addSnapshot(snap);
    bool saveInvalidSnap = _info.save();
    assert(saveInvalidSnap);
    (void) saveInvalidSnap;
    vespalib::mkdir(snapDir, false);
    save(snapDir, snapshot.getRankProfilesConfig());
    save(snapDir, snapshot.getIndexschemaConfig());
    save(snapDir, snapshot.getAttributesConfig());
    save(snapDir, snapshot.getSummaryConfig());
    save(snapDir, snapshot.getSummarymapConfig());
    save(snapDir, snapshot.getJuniperrcConfig());
    save(snapDir, snapshot.getDocumenttypesConfig());

    bool saveSchemaRes = snapshot.getSchemaSP()->saveToFile(snapDir + "/schema.txt");
    assert(saveSchemaRes);
    (void) saveSchemaRes;

    bool saveHistorySchemaRes = historySchema.saveToFile(snapDir + "/historyschema.txt");
    assert(saveHistorySchemaRes);
    (void) saveHistorySchemaRes;

    writeExtraConfigs(snapDir, snapshot);
    _info.validateSnapshot(serialNum);

    bool saveValidSnap = _info.save();
    assert(saveValidSnap);
    (void) saveValidSnap;
}

namespace {

// add an empty file if it's not already present
void addEmptyFile(vespalib::string snapDir, vespalib::string fileName)
{
    vespalib::string path = snapDir + "/" + fileName;
    if (access(path.c_str(), R_OK) == 0) {
        // exists OK
        return;
    }
    int fd = creat(path.c_str(), 0444);
    if (fd < 0) {
        LOG(error, "Could not create empty file '%s': %s", path.c_str(), strerror(errno));
        return;
    }
    close(fd);
}

}

void
FileConfigManager::loadConfig(const DocumentDBConfig &currentSnapshot,
                              search::SerialNum serialNum,
                              DocumentDBConfig::SP &loadedSnapshot,
                              search::index::Schema::SP &historySchema)
{
    vespalib::string snapDirBaseName(makeSnapDirBaseName(serialNum));
    vespalib::string snapDir(_baseDir + "/" + snapDirBaseName);
    config::DirSpec spec(snapDir);

    addEmptyFile(snapDir, "ranking-constants.cfg");
    addEmptyFile(snapDir, "imported-fields.cfg");

    DocumentDBConfigHelper dbc(spec, _docTypeName);

    typedef DocumenttypesConfig DTC;
    typedef DocumentDBConfig::DocumenttypesConfigSP DTCSP;
    DTCSP docTypesCfg(config::ConfigGetter<DTC>::getConfig("", spec).release());
    DocumentTypeRepo::SP repo;
    if (currentSnapshot.getDocumenttypesConfigSP().get() != NULL &&
        currentSnapshot.getDocumentTypeRepoSP().get() != NULL &&
        currentSnapshot.getDocumenttypesConfig() == *docTypesCfg) {
        docTypesCfg = currentSnapshot.getDocumenttypesConfigSP();
        repo = currentSnapshot.getDocumentTypeRepoSP();
    } else {
        repo.reset(new DocumentTypeRepo(*docTypesCfg));
    }

    auto filedistRpcConf = std::make_shared<FiledistributorrpcConfig>();

    /*
     * XXX: If non-default maintenance config is used then an extra config
     * snapshot is saved after replaying transaction log due to the use
     * of default values here instead of the current values from the config
     * server.
     */
    BootstrapConfig::SP bootstrap(
            new BootstrapConfig(1,
                                docTypesCfg,
                                repo,
                                _protonConfig,
                                filedistRpcConf,
                                currentSnapshot.getTuneFileDocumentDBSP()));
    dbc.forwardConfig(bootstrap);
    dbc.nextGeneration(0);

    Schema::UP newHistorySchema(new Schema);
    bool loadHistorySchemaRes = newHistorySchema->loadFromFile(snapDir + "/historyschema.txt");
    assert(loadHistorySchemaRes);
    (void) loadHistorySchemaRes;

    loadedSnapshot = dbc.getConfig();
    loadedSnapshot->setConfigId(_configId);
    loadedSnapshot->setExtraConfigs(readExtraConfigs(snapDir));
    historySchema.reset(newHistorySchema.release());
}


void
FileConfigManager::removeInvalid(void)
{
    typedef std::vector<SerialNum> RemVec;
    RemVec toRem;

    const SnapshotList &snaps = _info.snapshots();
    for (const auto &snap : snaps) {
        if (!snap.valid)
            toRem.push_back(snap.syncToken);
    }
    if (toRem.empty())
        return;

    for (const auto &serial : toRem) {
        vespalib::string snapDirBaseName(makeSnapDirBaseName(serial));
        vespalib::string snapDir(_baseDir + "/" + snapDirBaseName);
        try {
            FastOS_FileInterface::EmptyAndRemoveDirectory(snapDir.c_str());
        } catch (const std::exception &e) {
            LOG(warning, "Removing obsolete config directory '%s' failed due to %s", snapDir.c_str(), e.what());
        }
    }
    for (const auto &serial : toRem) {
        _info.removeSnapshot(serial);
    }
    bool saveRemInvalidSnap = _info.save();
    assert(saveRemInvalidSnap);
    (void) saveRemInvalidSnap;
}


void
FileConfigManager::prune(SerialNum serialNum)
{
    typedef std::vector<SerialNum> PruneVec;
    PruneVec toPrune;

    const SnapshotList &snaps = _info.snapshots();
    for (const auto &snap : snaps) {
        if (snap.valid && snap.syncToken <= serialNum)
            toPrune.push_back(snap.syncToken);
    }
    std::sort(toPrune.begin(), toPrune.end());
    if (!toPrune.empty())
        toPrune.pop_back();	// Keep newest old entry
    if (toPrune.empty())
        return;
    for (const auto &serial : toPrune) {
        _info.invalidateSnapshot(serial);
    }
    bool saveInvalidSnap = _info.save();
    assert(saveInvalidSnap);
    (void) saveInvalidSnap;
    removeInvalid();
}


bool
FileConfigManager::hasValidSerial(SerialNum serialNum) const
{
    IndexMetaInfo::Snapshot snap = _info.getSnapshot(serialNum);
    return snap.valid;
}


SerialNum
FileConfigManager::getPrevValidSerial(SerialNum serialNum) const
{
    SerialNum res = 0;
    const SnapshotList &snaps = _info.snapshots();
    for (const auto &snap : snaps) {
        if (!snap.valid || snap.syncToken >= serialNum)
            continue;
        if (res < snap.syncToken)
            res = snap.syncToken;
    }
    return res;
}


void
FileConfigManager::saveWipeHistoryConfig(SerialNum serialNum,
                                         fastos::TimeStamp wipeTimeLimit)
{
    vespalib::string snapDirBaseName(makeSnapDirBaseName(serialNum));
    vespalib::string snapDir(_baseDir + "/" + snapDirBaseName);

    if (hasValidSerial(serialNum))
        return;	// config already saved.

    SerialNum prevSerialNum = getPrevValidSerial(serialNum);

    assert(prevSerialNum > 0);

    Snapshot snap(false, serialNum, snapDirBaseName);
    _info.addSnapshot(snap);

    bool saveInvalidSnap = _info.save();
    assert(saveInvalidSnap);
    (void) saveInvalidSnap;

    vespalib::mkdir(snapDir, false);

    vespalib::string prevSnapDirBaseName(makeSnapDirBaseName(prevSerialNum));
    vespalib::string prevSnapDir(_baseDir + "/" + prevSnapDirBaseName);

    std::vector<vespalib::string> configs = getFileList(prevSnapDir);
    for (const auto &config : configs) {
        if (config == "historyschema.txt") {
            Schema::UP historySchema(new Schema);
            if (wipeTimeLimit != 0) {
                Schema oldHistorySchema;
                bool loadOldHistorySchemaRes =
                    oldHistorySchema.loadFromFile(prevSnapDir +
                                                   "/historyschema.txt");
                assert(loadOldHistorySchemaRes);
                (void) loadOldHistorySchemaRes;
                Schema::UP wipeSchema;
                wipeSchema = oldHistorySchema.getOldFields(wipeTimeLimit);
                historySchema = Schema::set_difference(oldHistorySchema,
                                                       *wipeSchema);
            }
            bool saveHistorySchemaRes =
                historySchema->saveToFile(snapDir + "/historyschema.txt");
            assert(saveHistorySchemaRes);
            (void) saveHistorySchemaRes;
            continue;
        }
        ConfigFile file(config,
                        prevSnapDir + "/" + config);
        file.save(snapDir);
    }
    _info.validateSnapshot(serialNum);
    bool saveValidSnap = _info.save();
    assert(saveValidSnap);
    (void) saveValidSnap;
}


void
FileConfigManager::serializeConfig(SerialNum serialNum, nbostream &stream)
{
    vespalib::string snapDirBaseName(makeSnapDirBaseName(serialNum));
    vespalib::string snapDir(_baseDir + "/" + snapDirBaseName);

    assert(hasValidSerial(serialNum));

    std::vector<vespalib::string> configs = getFileList(snapDir);
    uint32_t numConfigs = configs.size();
    stream << numConfigs;
    for (const auto &config : configs) {
        ConfigFile file(config,
                        snapDir + "/" + config);
        stream << file;
    }
}


void
FileConfigManager::deserializeConfig(SerialNum serialNum, nbostream &stream)
{
    vespalib::string snapDirBaseName(makeSnapDirBaseName(serialNum));
    vespalib::string snapDir(_baseDir + "/" + snapDirBaseName);

    bool skip = hasValidSerial(serialNum);

    Snapshot snap(false, serialNum, snapDirBaseName);
    if (!skip) {
        _info.addSnapshot(snap);
        bool saveInvalidSnap = _info.save();
        assert(saveInvalidSnap);
        (void) saveInvalidSnap;
        vespalib::mkdir(snapDir, false);
    }

    uint32_t numConfigs;
    stream >> numConfigs;
    for (uint32_t i = 0; i < numConfigs; ++i) {
        ConfigFile file;
        stream >> file;
        if (!skip)
            file.save(snapDir);
    }
    assert(stream.size() == 0);
    if (!skip) {
        _info.validateSnapshot(serialNum);
        bool saveValidSnap = _info.save();
        assert(saveValidSnap);
        (void) saveValidSnap;
    }
}


void
FileConfigManager::setProtonConfig(const ProtonConfigSP &protonConfig)
{
    _protonConfig = protonConfig;
}



} // namespace proton
