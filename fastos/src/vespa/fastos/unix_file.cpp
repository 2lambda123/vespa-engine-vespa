// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
******************************************************************************
* @author  Oivind H. Danielsen
* @date    Creation date: 2000-01-18
* @file
* Implementation of FastOS_UNIX_File methods.
*****************************************************************************/

#include <vespa/fastos/file.h>
#include <sys/vfs.h>
#include <sstream>
#include <stdexcept>

bool
FastOS_UNIX_File::SetPosition(int64_t desiredPosition)
{
    int64_t position = lseek(_filedes, desiredPosition, SEEK_SET);

    return (position == desiredPosition);
}


int64_t
FastOS_UNIX_File::GetPosition(void)
{
    return lseek(_filedes, 0, SEEK_CUR);
}


bool
FastOS_UNIX_File::Stat(const char *filename,
                       FastOS_StatInfo *statInfo)
{
    bool rc = false;

    struct stat stbuf;
    int lstatres;

    do {
        lstatres = lstat(filename, &stbuf);
    } while (lstatres == -1 && errno == EINTR);
    if (lstatres == 0) {
        statInfo->_error = FastOS_StatInfo::Ok;
        statInfo->_isRegular = S_ISREG(stbuf.st_mode);
        statInfo->_isDirectory = S_ISDIR(stbuf.st_mode);
        statInfo->_size = static_cast<int64_t>(stbuf.st_size);
        statInfo->_modifiedTime = stbuf.st_mtime;
        statInfo->_modifiedTimeNS = stbuf.st_mtim.tv_sec;
        statInfo->_modifiedTimeNS *= 1000000000;
        statInfo->_modifiedTimeNS += stbuf.st_mtim.tv_nsec;
        rc = true;
    } else {
        if (errno == ENOENT)
            statInfo->_error = FastOS_StatInfo::FileNotFound;
        else
            statInfo->_error = FastOS_StatInfo::Unknown;
    }

    return rc;
}


int FastOS_UNIX_File::GetMaximumFilenameLength (const char *pathName)
{
    return pathconf(pathName, _PC_NAME_MAX);
}

int FastOS_UNIX_File::GetMaximumPathLength(const char *pathName)
{
    return pathconf(pathName, _PC_PATH_MAX);
}

bool
FastOS_UNIX_File::MakeDirectory (const char *name)
{
    return (mkdir(name, 0775) == 0);
}


void
FastOS_UNIX_File::RemoveDirectory (const char *name)
{
    if ((rmdir(name) != 0) && (ERR_ENOENT != GetLastError())) {
        std::ostringstream os;
        os << "Remove of directory '" << name << "' failed with error :'" << getLastErrorString() << "'";
        throw std::runtime_error(os.str());
    }
}


std::string
FastOS_UNIX_File::getCurrentDirectory(void)
{
    std::string res;
    int maxPathLen = FastOS_File::GetMaximumPathLength(".");
    if (maxPathLen == -1)
        maxPathLen = 16384;
    else if (maxPathLen < 512)
        maxPathLen = 512;

    char *currentDir = new char [maxPathLen + 1];

    if (getcwd(currentDir, maxPathLen) != NULL)
    {
        res = currentDir;
    }
    delete [] currentDir;

    return res;
}


unsigned int
FastOS_UNIX_File::CalcAccessFlags(unsigned int openFlags)
{
    unsigned int accessFlags=0;

    if ((openFlags & (FASTOS_FILE_OPEN_READ |
                      FASTOS_FILE_OPEN_DIRECTIO)) != 0) {
        if ((openFlags & FASTOS_FILE_OPEN_WRITE) != 0) {
            // Open for reading and writing
            accessFlags = O_RDWR;
        } else {
            // Open for reading only
            accessFlags = O_RDONLY;
        }
    } else {
        // Open for writing only
        accessFlags = O_WRONLY;
    }

    if (((openFlags & FASTOS_FILE_OPEN_EXISTING) == 0) &&
        ((openFlags & FASTOS_FILE_OPEN_WRITE) != 0)) {
        // Create file if it does not exist
        accessFlags |= O_CREAT;
    }

#if defined(O_SYNC)
    if ((openFlags & FASTOS_FILE_OPEN_SYNCWRITES) != 0)
        accessFlags |= O_SYNC;
#elif defined(O_FSYNC)
    if ((openFlags & FASTOS_FILE_OPEN_SYNCWRITES) != 0)
        accessFlags |= O_FSYNC;
#endif

    if ((openFlags & FASTOS_FILE_OPEN_DIRECTIO) != 0) {
        accessFlags |= O_DIRECT | O_DSYNC | O_RSYNC;
    }

    if ((openFlags & FASTOS_FILE_OPEN_TRUNCATE) != 0) {
        // Truncate file on open
        accessFlags |= O_TRUNC;
    }
    return accessFlags;
}

bool
FastOS_UNIX_File::Open(unsigned int openFlags, const char *filename)
{
    bool rc = false;
    assert(_filedes == -1);

    if ((openFlags & FASTOS_FILE_OPEN_STDFLAGS) != 0) {
        FILE *file;

        switch(openFlags & FASTOS_FILE_OPEN_STDFLAGS) {
        case FASTOS_FILE_OPEN_STDIN:
            file = stdin;
            SetFileName("stdin");
            break;

        case FASTOS_FILE_OPEN_STDOUT:
            file = stdout;
            SetFileName("stdout");
            break;

        case FASTOS_FILE_OPEN_STDERR:
            file = stderr;
            SetFileName("stderr");
            break;

        default:
            file = NULL;
            fprintf(stderr, "Invalid open-flags %08X\n", openFlags);
            abort();
        }

        _filedes = file->_fileno;
        _openFlags = openFlags;
        rc = true;
    } else {
        if (filename != NULL)
            SetFileName(filename);

        unsigned int accessFlags = CalcAccessFlags(openFlags);

        _filedes = open(_filename, accessFlags, 0664);

        rc = (_filedes != -1);

        if (rc) {
            _openFlags = openFlags;
            if (_mmapEnabled) {
                int64_t filesize = GetSize();
                size_t mlen = static_cast<size_t>(filesize);
                if (static_cast<int64_t>(mlen) == filesize && mlen > 0) {
                    void *mbase = mmap(NULL, mlen, PROT_READ, MAP_SHARED | _mmapFlags, _filedes, static_cast<off_t>(0));
                    if (static_cast<void *>(mbase) != reinterpret_cast<void *>(-1)) {
                        int fadviseOptions = getFAdviseOptions();
                        int eCode(0);
                        if (POSIX_FADV_RANDOM == fadviseOptions) {
                            eCode = posix_madvise(mbase, mlen, POSIX_MADV_RANDOM);
                        } else if (POSIX_FADV_SEQUENTIAL == fadviseOptions) {
                            eCode = posix_madvise(mbase, mlen, POSIX_MADV_SEQUENTIAL);
                        }
                        if (eCode != 0) {
                            fprintf(stderr, "Failed: posix_madvise(%p, %ld, %d) = %d\n", mbase, mlen, fadviseOptions, eCode);
                        }
                        _mmapbase = mbase;
                        _mmaplen = mlen;
                    } else {
                        std::ostringstream os;
                        os << "mmap of file '" << GetFileName() << "' with flags '" << std::hex << (MAP_SHARED | _mmapFlags) << std::dec
                           << "' failed with error :'" << getErrorString(GetLastOSError()) << "'";
                        throw std::runtime_error(os.str());
                    }
                }
            }
        }

    }

    return rc;
}

void FastOS_UNIX_File::dropFromCache() const
{
    posix_fadvise(_filedes, 0, 0, POSIX_FADV_DONTNEED);
}


bool
FastOS_UNIX_File::Close(void)
{
    bool ok = true;

    if (_filedes >= 0) {
        if ((_openFlags & FASTOS_FILE_OPEN_STDFLAGS) != 0)
            ok = true;
        else {
            do {
                ok = (close(_filedes) == 0);
            } while (!ok && errno == EINTR);
        }

        if (_mmapbase != NULL) {
            madvise(_mmapbase, _mmaplen, MADV_DONTNEED);
            munmap(static_cast<char *>(_mmapbase), _mmaplen);
            _mmapbase = NULL;
            _mmaplen = 0;
        }

        _filedes = -1;
    }

    _openFlags = 0;

    return ok;
}


int64_t
FastOS_UNIX_File::GetSize(void)
{
    int64_t fileSize=-1;
    struct stat stbuf;

    assert(IsOpened());

    int res = fstat(_filedes, &stbuf);

    if (res == 0)
        fileSize = stbuf.st_size;

    return fileSize;
}


time_t
FastOS_UNIX_File::GetModificationTime(void)
{
    struct stat stbuf;
    int res;

    assert(IsOpened());

    res = fstat(_filedes, &stbuf);
    assert(res == 0);
    (void) res;

    return stbuf.st_mtime;
}


bool
FastOS_UNIX_File::Delete(const char *name)
{
    return (unlink(name) == 0);
}


bool
FastOS_UNIX_File::Delete(void)
{
    assert(!IsOpened());
    assert(_filename != NULL);

    return (unlink(_filename) == 0);
}

bool FastOS_UNIX_File::Rename (const char *currentFileName,
                               const char *newFileName)
{
    bool rc = false;

    // Enforce documentation. If the destination file exists,
    // fail Rename.
    FastOS_StatInfo statInfo;
    if (!FastOS_File::Stat(newFileName, &statInfo))
    {
        rc = (rename(currentFileName, newFileName) == 0);
    } else {
        errno = EEXIST;
    }
    return rc;
}

bool
FastOS_UNIX_File::Sync(void)
{
    assert(IsOpened());

    return (fsync(_filedes) == 0);
}


bool
FastOS_UNIX_File::SetSize(int64_t newSize)
{
    bool rc = false;

    if (ftruncate(_filedes, static_cast<off_t>(newSize)) == 0)
        rc = SetPosition(newSize);

    return rc;
}


FastOS_File::Error
FastOS_UNIX_File::TranslateError (const int osError)
{
    switch(osError) {
    case ENOENT:     return ERR_NOENT;      // No such file or directory
    case ENOMEM:     return ERR_NOMEM;      // Not enough memory
    case EACCES:     return ERR_ACCES;      // Permission denied
    case EEXIST:     return ERR_EXIST;      // File exists
    case EINVAL:     return ERR_INVAL;      // Invalid argument
    case ENOSPC:     return ERR_NOSPC;      // No space left on device
    case EINTR:      return ERR_INTR;       // interrupt
    case EAGAIN:     return ERR_AGAIN;      // Resource unavailable, try again
    case EBUSY:      return ERR_BUSY;       // Device or resource busy
    case EIO:        return ERR_IO;         // I/O error
    case EPERM:      return ERR_PERM;       // Not owner
    case ENODEV:     return ERR_NODEV;      // No such device
    case ENXIO:      return ERR_NXIO;       // Device not configured
    }

    if (osError == FASTOS_ENFILE_VERIFIED)
        return ERR_NFILE;

    if (osError == FASTOS_EMFILE_VERIFIED)
        return ERR_MFILE;

    return ERR_UNKNOWN;
}


std::string
FastOS_UNIX_File::getErrorString(const int osError)
{
    char errorBuf[100];
    const char *errorString = strerror_r(osError, errorBuf, sizeof(errorBuf));

    return std::string(errorString);
}


int64_t FastOS_UNIX_File::GetFreeDiskSpace (const char *path)
{
    int64_t freeSpace = -1;

    struct statfs statBuf;
    int statVal = -1;
    statVal = statfs(path, &statBuf);
    if (statVal == 0)
        freeSpace = int64_t(statBuf.f_bavail) * int64_t(statBuf.f_bsize);

    return freeSpace;
}

FastOS_UNIX_DirectoryScan::FastOS_UNIX_DirectoryScan(const char *searchPath)
    : FastOS_DirectoryScanInterface(searchPath),
      _statRun(false),
      _isDirectory(false),
      _isRegular(false),
      _statName(NULL),
      _statFilenameP(NULL),
      _dir(NULL),
      _dp(NULL)
{
    _dir = opendir(searchPath);

    const int minimumLength = 512 + 1;
    const int defaultLength = 16384;

    int maxNameLength = FastOS_File::GetMaximumFilenameLength(searchPath);
    int maxPathLength = FastOS_File::GetMaximumPathLength(searchPath);
    int nameLength = maxNameLength + 1 + maxPathLength;

    if ((maxNameLength == -1) ||
       (maxPathLength == -1) ||
       (nameLength < minimumLength))
    {
        nameLength = defaultLength;
    }

    _statName = new char [nameLength + 1];  // Include null

    strcpy(_statName, searchPath);
    strcat(_statName, "/");

    _statFilenameP = &_statName[strlen(_statName)];
}


FastOS_UNIX_DirectoryScan::~FastOS_UNIX_DirectoryScan(void)
{
    if (_dir != NULL) {
        closedir(_dir);
        _dir = NULL;
    }
    delete [] _statName;
}


bool
FastOS_UNIX_DirectoryScan::ReadNext(void)
{
    bool rc = false;

    _statRun = false;

    if (_dir != NULL) {
        _dp = readdir(_dir);
        rc = _dp != NULL;
    }

    return rc;
}


void
FastOS_UNIX_DirectoryScan::DoStat(void)
{
    struct stat stbuf;

    assert(_dp != NULL);

    strcpy(_statFilenameP, _dp->d_name);

    if (lstat(_statName, &stbuf) == 0) {
        _isRegular = S_ISREG(stbuf.st_mode);
        _isDirectory = S_ISDIR(stbuf.st_mode);
    } else {
        printf("lstat failed for [%s]\n", _dp->d_name);
        _isRegular = false;
        _isDirectory = false;
    }

    _statRun = true;
}


bool
FastOS_UNIX_DirectoryScan::IsDirectory(void)
{
    if (!_statRun)
        DoStat();

    return _isDirectory;
}


bool
FastOS_UNIX_DirectoryScan::IsRegular(void)
{
    if (!_statRun)
        DoStat();

    return _isRegular;
}


const char *
FastOS_UNIX_DirectoryScan::GetName(void)
{
    assert(_dp != NULL);

    return static_cast<const char *>(_dp->d_name);
}


bool
FastOS_UNIX_DirectoryScan::IsValidScan(void) const
{
    return _dir != NULL;
}
