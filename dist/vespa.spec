
# Hack to speed up jar packing for now
%define __jar_repack %{nil}

# Force special prefix for Vespa
%define _prefix /opt/yahoo/vespa

Name:           vespa
Version:        VESPA_VERSION
Release:        1%{?dist}
Summary:        Vespa - The open big data serving engine
Group:          Applications/Databases
License:        Commercial
URL:            http://vespa.corp.yahoo.com
Source0:        vespa-%{version}.tar.gz

BuildRequires: epel-release 
BuildRequires: centos-release-scl
BuildRequires: devtoolset-4-gcc-c++
BuildRequires: devtoolset-4-libatomic-devel
BuildRequires: Judy-devel
BuildRequires: cmake3
BuildRequires: lz4-devel
BuildRequires: zlib-devel
BuildRequires: maven
BuildRequires: libicu-devel
BuildRequires: llvm-devel
BuildRequires: java-1.8.0-openjdk-devel
BuildRequires: openssl-devel
BuildRequires: rpm-build
BuildRequires: make
BuildRequires: vespa-boost-devel >= 1.59
BuildRequires: vespa-cppunit-devel >= 1.12.1
BuildRequires: vespa-libtorrent-devel >= 1.0.9
BuildRequires: vespa-zookeeper-c-client-devel >= 3.4.8
BuildRequires: systemd
Requires: epel-release 
Requires: Judy
Requires: lz4
Requires: zlib
Requires: libicu
Requires: llvm
Requires: java-1.8.0-openjdk
Requires: openssl
Requires: vespa-boost >= 1.59
Requires: vespa-cppunit >= 1.12.1
Requires: vespa-libtorrent >= 1.0.9
Requires: vespa-zookeeper-c-client >= 3.4.8
Requires(pre): shadow-utils

# Ugly workaround because vespamalloc/src/vespamalloc/malloc/mmap.cpp uses the private
# _dl_sym function.
Provides: libc.so.6(GLIBC_PRIVATE)(64bit)

%description

Vespa - The open big data serving engine

%prep
%setup -q

%build
source /opt/rh/devtoolset-4/enable || true
sh bootstrap.sh
mvn install -DskipTests -Dmaven.javadoc.skip=true
cmake3 -DCMAKE_INSTALL_PREFIX=%{_prefix} \
       -DJAVA_HOME=/usr/lib/jvm/java-openjdk \
       -DEXTRA_LINK_DIRECTORY="/opt/yahoo/vespa-boost/lib;/opt/yahoo/vespa-libtorrent/lib;/opt/yahoo/vespa-zookeeper-c-client/lib;/opt/yahoo/vespa-cppunit/lib;/usr/lib64/llvm" \
       -DEXTRA_INCLUDE_DIRECTORY="/opt/yahoo/vespa-boost/include;/opt/yahoo/vespa-libtorrent/include;/opt/yahoo/vespa-zookeeper-c-client/include;/opt/yahoo/vespa-cppunit/include" \
       -DCMAKE_INSTALL_RPATH="%{_prefix}/lib64;/opt/yahoo/vespa-boost/lib;/opt/yahoo/vespa-libtorrent/lib;/opt/yahoo/vespa-zookeeper-c-client/lib;/opt/yahoo/vespa-cppunit/lib;/usr/lib/jvm/java-1.8.0/jre/lib/amd64/server" \
       -DCMAKE_BUILD_RPATH=%{_prefix}/lib64 \
       .

make %{_smp_mflags}

%install
rm -rf $RPM_BUILD_ROOT
make install DESTDIR=%{buildroot}

mkdir -p %{buildroot}/usr/lib/systemd/system
cp %{buildroot}/%{_prefix}/etc/systemd/system/vespa.service %{buildroot}/usr/lib/systemd/system
cp %{buildroot}/%{_prefix}/etc/systemd/system/vespa-configserver.service %{buildroot}/usr/lib/systemd/system

%clean
rm -rf $RPM_BUILD_ROOT

%pre
getent group vespa >/dev/null || groupadd -r vespa
getent passwd vespa >/dev/null || \
    useradd -r -g vespa -d %{_prefix} -s /sbin/nologin \
    -c "Create owner of all Vespa data files" vespa
echo "pathmunge %{_prefix}/bin" > /etc/profile.d/vespa.sh
echo "export VESPA_HOME=%{_prefix}" >> /etc/profile.d/vespa.sh
chmod +x /etc/profile.d/vespa.sh
exit 0

%post
%systemd_post vespa-configserver.service 
%systemd_post vespa.service 

%preun
%systemd_preun vespa.service
%systemd_preun vespa-configserver.service

%postun
%systemd_postun_with_restart vespa.service 
%systemd_postun_with_restart vespa-configserver.service 
rm -f /etc/profile.d/vespa.sh
userdel vespa 

%files
%defattr(-,vespa,vespa,-)
%doc
%{_prefix}/*
%attr(644,root,root) /usr/lib/systemd/system/vespa.service
%attr(644,root,root) /usr/lib/systemd/system/vespa-configserver.service

%changelog
