// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".filedownloadermanager");

#include "filedownloadermanager.h"

#include <iterator>
#include <sstream>
#include <thread>

using namespace std::literals;
namespace ph = std::placeholders;

using filedistribution::FileDownloaderManager;

namespace {
void logStartDownload(const std::set<std::string> & filesToDownload) {
    std::ostringstream msg;
    msg <<"StartDownloads:" <<std::endl;
    std::copy(filesToDownload.begin(), filesToDownload.end(),
              std::ostream_iterator<std::string>(msg, "\n"));
    LOG(debug, msg.str().c_str());
}
} //anonymous namespace

FileDownloaderManager::FileDownloaderManager(
    const std::shared_ptr<FileDownloader>& downloader,
    const std::shared_ptr<FileDistributionModel>& model)

    :_fileDownloader(downloader),
     _fileDistributionModel(model),
     _startDownloads(this),
     _setFinishedDownloadingStatus(this)
{}

FileDownloaderManager::~FileDownloaderManager()  {
    LOG(debug, "Deconstructing FileDownloaderManager");
}

void
FileDownloaderManager::start()
{
    _downloadFailedConnection =
        downloadFailed().connect(
                DownloadFailedSignal::slot_type(std::bind(&FileDownloaderManager::removePeerStatus, this, ph::_1)).
                track(shared_from_this()));

    _downloadCompletedConnection =
        downloadCompleted().connect(
                DownloadCompletedSignal::slot_type(_setFinishedDownloadingStatus).
                track(shared_from_this()));

    _filesToDownloadChangedConnection =
        _fileDistributionModel->_filesToDownloadChanged.connect(
                FileDistributionModel::FilesToDownloadChangedSignal::slot_type(std::ref(_startDownloads)).
                track(shared_from_this()));
}

boost::optional< boost::filesystem::path >
FileDownloaderManager::getPath(const std::string& fileReference) {
    return _fileDownloader->pathToCompletedFile(fileReference);
}

void
FileDownloaderManager::downloadFile(const std::string& fileReference) {
    {
        LockGuard updateFilesToDownloadGuard(_updateFilesToDownloadMutex);
        _startDownloads.downloadFile(fileReference);
    }

    //if the file is already downloading but not completed before the above call,
    //the finished download callback might come before the interested party
    //has called connectFinishedDownloadingHandler.
    //An explicit call is therefore used to mitigate this problem:
    //Do not hold updateFilesToDownloadMutex when calling this, as it might cause deadlock.
    _fileDownloader->signalIfFinishedDownloading(fileReference);
}

void
FileDownloaderManager::removePeerStatus(const std::string& fileReference) {
    //TODO: Simplify by using separate thread for removal:
    //currently called via StartDownloads which already holds a lock on updateFilesToDownloadMutex.

    _fileDistributionModel->removePeer(fileReference);
}

void
FileDownloaderManager::StartDownloads::downloadFile(const std::string& fileReference) {
    if (!_parent._fileDownloader->hasTorrent(fileReference)) {
        Buffer torrent(_parent._fileDistributionModel->getFileDBModel().getFile(fileReference));

        _parent._fileDistributionModel->addPeer(fileReference);
        _parent._fileDownloader->addTorrent(fileReference, torrent);
    }
}


void
FileDownloaderManager::StartDownloads::operator()() {

    DirectoryGuard::UP guard = _parent._fileDownloader->getGuard();
    LockGuard updateFilesToDownloadGuard(_parent._updateFilesToDownloadMutex);

    std::set<std::string> filesToDownload = _parent._fileDistributionModel->getFilesToDownload();
    logStartDownload(filesToDownload);

    std::for_each(filesToDownload.begin(), filesToDownload.end(),
        std::bind(&StartDownloads::downloadFile, this, ph::_1));

    _parent._fileDownloader->removeAllTorrentsBut(filesToDownload);
}

FileDownloaderManager::StartDownloads::StartDownloads(FileDownloaderManager* parent)
    :_parent(*parent)
{}


FileDownloaderManager::SetFinishedDownloadingStatus::SetFinishedDownloadingStatus(
        FileDownloaderManager* parent)
    :_parent(*parent)
{}

void
FileDownloaderManager::SetFinishedDownloadingStatus::operator()(
        const std::string& fileReference, const boost::filesystem::path&) {

    //Prevent concurrent modifications to peer node in zk.
    LockGuard updateFilesToDownloadGuard(_parent._updateFilesToDownloadMutex);

    try {
        _parent._fileDistributionModel->peerFinished(fileReference);
    } catch(const FileDistributionModel::NotPeer&) {  //Probably a concurrent removal of the torrent.

        //improve chance of libtorrent session being updated.
        std::this_thread::sleep_for(100ms);
        if (_parent._fileDownloader->hasTorrent(fileReference)) {

            _parent._fileDistributionModel->addPeer(fileReference);
            _parent._fileDistributionModel->peerFinished(fileReference);
        } else {
            LOG(debug, "OK: Torrent '%s' finished concurrently with its removal.", fileReference.c_str());
        }
    }
}
