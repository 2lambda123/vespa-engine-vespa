// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#pragma once

#include<boost/optional.hpp>
#include<boost/filesystem/path.hpp>
#include<boost/signals2.hpp>

namespace filedistribution {

class FileProvider
{
public:
    using SP = std::shared_ptr<FileProvider>;
    typedef boost::signals2::signal<void (const std::string& /* fileReference */,
            const boost::filesystem::path&)>
        DownloadCompletedSignal;
    typedef DownloadCompletedSignal::slot_type DownloadCompletedHandler;

    enum FailedDownloadReason {
        FileReferenceDoesNotExist,
        FileReferenceRemoved
    };

    typedef boost::signals2::signal<void (const std::string& /* fileReference */,
                                   FailedDownloadReason)>
        DownloadFailedSignal;
    typedef DownloadFailedSignal::slot_type DownloadFailedHandler;

    virtual boost::optional<boost::filesystem::path> getPath(const std::string& fileReference) = 0;
    virtual void downloadFile(const std::string& fileReference) = 0;

    virtual ~FileProvider() {}

    //Signals
    virtual DownloadCompletedSignal& downloadCompleted() = 0;
    virtual DownloadFailedSignal& downloadFailed() = 0;
};

} //namespace filedistribution

