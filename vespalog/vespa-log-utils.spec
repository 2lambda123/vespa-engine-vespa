# Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

# Force special prefix for Vespa
%define _prefix /opt/vespa

Name:           vespa-log-utils
Version:        %version
Release:        1%{?dist}
BuildArch:      x86_64
Summary:        Vespa log utilities
Group:          Applications/Databases
License:        Commercial
URL:            http://vespa.ai

Requires: bash
Requires: vespa-base = %{version}

Conflicts: vespa

%description
Utilities for reading Vespa log files.

%install
bin_dir=%?buildroot%_prefix/bin
mkdir -p "$bin_dir"
cp vespalog/src/vespa-logfmt/vespa-logfmt.pl "${bin_dir}/vespa-logfmt"
chmod 555 "${bin_dir}/vespa-logfmt"
ln -s "vespa-logfmt" "${bin_dir}/logfmt"

%clean
rm -rf %buildroot

%files
%defattr(-,vespa,vespa,-)
%_prefix/*
