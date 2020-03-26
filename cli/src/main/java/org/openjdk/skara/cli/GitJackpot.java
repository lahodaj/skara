/*
 * Copyright (c) 2018, 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.cli;

import org.openjdk.skara.args.*;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.version.Version;

import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

public class GitJackpot {

    private static String arg(String name, Arguments args, ReadOnlyRepository repo) throws IOException {
        if (args.contains(name)) {
            return args.get(name).asString();
        }

        var config = repo.config("webrev." + name);
        if (config.size() == 1) {
            return config.get(0);
        }

        return null;
    }

    private static void die(String message) {
        System.err.println(message);
        System.exit(1);
    }

    private static Hash resolve(ReadOnlyRepository repo, String ref) {
        var message = "error: could not resolve reference '" + ref + "'";
        try {
            var hash = repo.resolve(ref);
            if (!hash.isPresent()) {
                die(message);
            }
            return hash.get();
        } catch (IOException e) {
            die(message);
            return null; // impossible
        }
    }

    public static void main(String[] args) throws Exception {
        var flags = List.of(
            Option.shortcut("r")
                  .fullname("rev")
                  .describe("REV")
                  .helptext("Compare against a specified revision")
                  .optional(),
            Option.shortcut("")
                  .fullname("remote")
                  .describe("NAME")
                  .helptext("Use remote to calculate outgoing changes")
                  .optional(),
            Switch.shortcut("m")
                  .fullname("mercurial")
                  .helptext("Deprecated: force use of mercurial")
                  .optional(),
            Switch.shortcut("N")
                  .fullname("no-outgoing")
                  .helptext("Do not compare against remote, use only 'status'")
                  .optional(),
            Switch.shortcut("v")
                  .fullname("version")
                  .helptext("Print the version of this tool")
                  .optional());

        var inputs = List.of(
            Input.position(0)
                 .describe("FILE")
                 .singular()
                 .optional());

        var parser = new ArgumentParser("git jackpot", flags, inputs);
        var arguments = parser.parse(args);

        var version = Version.fromManifest().orElse("unknown");
        if (arguments.contains("version")) {
            System.out.println("git-jackpot version: " + version);
            System.exit(0);
        }

        var cwd = Paths.get("").toAbsolutePath();
        var repository = Repository.get(cwd);
        if (!repository.isPresent()) {
            System.err.println(String.format("error: %s is not a repository", cwd.toString()));
            System.exit(1);
        }
        var repo = repository.get();
        var isMercurial = arguments.contains("mercurial");
        var noOutgoing = arguments.contains("no-outgoing");
        if (!noOutgoing) {
            var config = repo.config("webrev.no-outgoing");
            if (config.size() == 1) {
                var enabled = Set.of("TRUE", "ON", "1", "ENABLED");
                noOutgoing = enabled.contains(config.get(0).toUpperCase());
            }
        }

        var rev = arguments.contains("rev") ? resolve(repo, arguments.get("rev").asString()) : null;
        if (rev == null) {
            if (isMercurial) {
                resolve(repo, noOutgoing ? "tip" : "min(outgoing())^");
            } else {
                if (noOutgoing) {
                    rev = resolve(repo, "HEAD");
                } else {
                    var currentUpstreamBranch = repo.currentBranch().flatMap(b -> {
                        try {
                            return repo.upstreamFor(b);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                    if (currentUpstreamBranch.isPresent()) {
                        rev = resolve(repo, currentUpstreamBranch.get());
                    } else {
                        String remote = arg("remote", arguments, repo);
                        if (remote == null) {
                            var remotes = repo.remotes();
                            if (remotes.size() == 0) {
                                System.err.println("error: no remotes present, cannot figure out outgoing changes");
                                System.err.println("       Use --rev to specify revision to compare against");
                                System.exit(1);
                            } else if (remotes.size() == 1) {
                                remote = remotes.get(0);
                            } else {
                                if (remotes.contains("origin")) {
                                    remote = "origin";
                                } else {
                                    System.err.println("error: multiple remotes without origin remote present, cannot figure out outgoing changes");
                                    System.err.println("       Use --rev to specify revision to compare against");
                                    System.exit(1);
                                }
                            }
                        }

                        var head = repo.head();
                        var shortestDistance = -1;
                        var pullPath = repo.pullPath(remote);
                        var remoteBranches = repo.remoteBranches(remote);
                        for (var remoteBranch : remoteBranches) {
                            var fetchHead = repo.fetch(URI.create(pullPath), remoteBranch.name());
                            var mergeBase = repo.mergeBase(fetchHead, head);
                            var distance = repo.commitMetadata(mergeBase, head).size();
                            if (shortestDistance == -1 || distance < shortestDistance) {
                                rev = mergeBase;
                                shortestDistance = distance;
                            }
                        }
                    }
                }
            }
        }

        List<Path> files = List.of();
        if (arguments.at(0).isPresent()) {
            var path = arguments.at(0).via(Path::of);
            if (path.equals(Path.of("-"))) {
                var reader = new BufferedReader(new InputStreamReader(System.in));
                files = reader.lines().map(Path::of).collect(Collectors.toList());
            } else {
                files = Files.readAllLines(path).stream().map(Path::of).collect(Collectors.toList());
            }
        }

        var diff = repo.diff(rev, files);
        var modules = diff.patches()
                          .stream()
                          .flatMap(p -> p.source().path().stream())
                          .filter(p -> p.getNameCount() > 1)
                          .filter(p -> "src".equals(p.getName(0).toString()))
                          .map(p -> p.getName(1).toString())
                          .collect(Collectors.joining(" "));

        var patchFile = Files.createTempFile("patch", ".patch");

        diff.toFile(patchFile);

        var pb = new ProcessBuilder("make", "JACKPOT_MODULES=" + modules, "JACKPOT_EXTRA_OPTIONS=--filter-patch " + patchFile.toString(), "jackpot");
        pb.directory(repo.root().toFile());
        pb.inheritIO();
        System.exit(pb.start().waitFor());
    }

}
