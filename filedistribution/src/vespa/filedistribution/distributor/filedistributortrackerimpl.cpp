// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/fastos/fastos.h>
#include "filedistributortrackerimpl.h"
#include <cmath>
#include <libtorrent/tracker_manager.hpp>
#include <libtorrent/torrent.hpp>
#include <vespa/filedistribution/model/filedistributionmodel.h>
#include "filedownloader.h"
#include "hostname.h"

#include <vespa/log/log.h>
LOG_SETUP(".filedistributiontrackerimpl");

using filedistribution::FileDistributorTrackerImpl;
using filedistribution::FileDownloader;
using filedistribution::FileDistributionModel;
using filedistribution::Scheduler;
using filedistribution::ExceptionRethrower;
using filedistribution::TorrentSP;

typedef FileDistributionModel::PeerEntries PeerEntries;

namespace asio = boost::asio;

namespace {

void
filterSelf(FileDistributionModel::PeerEntries& peers,
           const std::string& hostName,
           int port)
{
    FileDistributionModel::PeerEntries::iterator
        i = peers.begin(),
        currEnd = peers.end();

    while ( i != currEnd ) {
        //hostName is currently used in the ip field
        if (i->ip == hostName && i->port == port) {
            --currEnd;
            std::swap(*i, *currEnd);
        } else {
            ++i;
        }
    }

    peers.erase(currEnd, peers.end());
}

void resolveIPAddresses(PeerEntries& peers) {
    for (auto& p: peers) {
        try {
            p.ip = filedistribution::lookupIPAddress(p.ip);
        } catch (filedistribution::FailedResolvingHostName& e) {
            LOG(info, "Failed resolving address %s", p.ip.c_str());
        }
    }
}

struct TrackingTask : public Scheduler::Task {
    int _numTimesRescheduled;

    libtorrent::tracker_request _trackerRequest;
    boost::weak_ptr<libtorrent::torrent> _torrent;
    std::weak_ptr<FileDownloader> _downloader;
    std::shared_ptr<FileDistributionModel> _model;

    TrackingTask(Scheduler& scheduler,
                 const libtorrent::tracker_request& trackerRequest,
                 const TorrentSP & torrent,
                 const std::weak_ptr<FileDownloader>& downloader,
                 const std::shared_ptr<FileDistributionModel>& model)
        : Task(scheduler),
          _numTimesRescheduled(0),
          _trackerRequest(trackerRequest),
          _torrent(torrent),
          _downloader(downloader),
          _model(model)
    {}

    //TODO: refactor
    void doHandle() {
        if (std::shared_ptr<FileDownloader> downloader = _downloader.lock()) {
            //All torrents must be destructed before the session is destructed.
            //It's okay to prevent the torrent from expiring here
            //since the session can't be destructed while
            //we hold a shared_ptr to the downloader.
            if (TorrentSP torrent = _torrent.lock()) {
                PeerEntries peers = getPeers(downloader);

                if (!peers.empty()) {
                    torrent->session().m_io_service.dispatch(
                        [torrent_weak_ptr = _torrent, trackerRequest = _trackerRequest, peers = peers]() mutable {
                            if (auto torrent_sp = torrent_weak_ptr.lock()) {
                                torrent_sp->tracker_response(
			            trackerRequest,
				    libtorrent::address(),
				    std::list<libtorrent::address>(),
				    peers,
				    -1, -1, -1, -1, -1,
				    libtorrent::address(), "trackerid");
                            }
                        });
                }

                if (peers.size() < 5) {
                    reschedule();
                }
            }
        }
    }

    PeerEntries getPeers(const std::shared_ptr<FileDownloader>& downloader) {
        std::string fileReference = downloader->infoHash2FileReference(_trackerRequest.info_hash);

        const size_t recommendedMaxNumberOfPeers = 30;
        PeerEntries peers = _model->getPeers(fileReference, recommendedMaxNumberOfPeers);

        //currently, libtorrent stops working if it tries to connect to itself.
        filterSelf(peers, downloader->_hostName, downloader->_port);
        resolveIPAddresses(peers);
        for (const auto& peer: peers) {
            LOG(debug, "Returning peer with ip %s", peer.ip.c_str());
        }

        return peers;
    }

    void reschedule() {
        if (_numTimesRescheduled < 5) {
            double fudgeFactor = 0.1;
            schedule(boost::posix_time::seconds(static_cast<int>(
                                    std::pow(3., _numTimesRescheduled) + fudgeFactor)));
            _numTimesRescheduled++;
        }
    }
};


void
workerFunction(std::shared_ptr<ExceptionRethrower> exceptionRethrower, asio::io_service& ioService)
{
    while (!boost::this_thread::interruption_requested()) {
        try {
            //No reset needed after handling exceptions.
            ioService.run();
        } catch(const boost::thread_interrupted&) {
            LOG(debug, "Tracker worker thread interrupted.");
            throw;
        } catch(...) {
            exceptionRethrower->store(boost::current_exception());
        }
    }
}

} //anonymous namespace


FileDistributorTrackerImpl::FileDistributorTrackerImpl(
        const std::shared_ptr<FileDistributionModel>& model,
        const std::shared_ptr<ExceptionRethrower>& exceptionRethrower)
    :_exceptionRethrower(exceptionRethrower),
     _model(model)
{}


FileDistributorTrackerImpl::~FileDistributorTrackerImpl() {
    LOG(debug, "Deconstructing FileDistributorTrackerImpl");

    LockGuard guard(_mutex);
    _scheduler.reset();
}


void
FileDistributorTrackerImpl::trackingRequest(
        libtorrent::tracker_request& request,
        const TorrentSP & torrent)
{
    LockGuard guard(_mutex);

    if (torrent != TorrentSP()) {
        std::shared_ptr<TrackingTask> trackingTask(new TrackingTask(
                        *_scheduler.get(), request, torrent, _downloader, _model));

        trackingTask->scheduleNow();
    }
}


void
FileDistributorTrackerImpl::setDownloader(const std::shared_ptr<FileDownloader>& downloader)
{
    LockGuard guard(_mutex);

    _scheduler.reset();
    _downloader = downloader;

    if (downloader) {
        _scheduler.reset(new Scheduler(boost::bind(&workerFunction, _exceptionRethrower, _1)));
    }
}
