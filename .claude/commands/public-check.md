---
name: public-check
description: Search what the next /deploy will publish (every commit since the last release tag, its added lines and its message, and the uncommitted changes; or, with zip, what the build generated into the release zip) for anything that identifies the local environment. Reports; changes nothing without the user's OK.
category: Maintenance
tags: [release, privacy, audit]
argument-hint: "[empty | <tag or commit to start from> | zip <release zip>]"
---

Everything `/deploy` pushes is public: `github` is a public mirror, and so are the release zip and its notes. This command searches only what the next `/deploy` will add, never the whole repository: the lines each commit since the last release tag adds (for a merge, the lines it adds itself), the uncommitted changes to tracked files, and the commit messages. It reads every commit on its own, not the difference between the tag and today, because `/deploy` pushes each of those commits: a line that one commit adds and a later one removes is still published. With `zip <path>` it reads the release zip instead, for what no commit shows: the files and jar entries the build generated on this machine. `/deploy` runs that after its build. AGENTS.md's "Public Repository" section is the rule it checks. It reports and changes nothing until the user agrees. Use `/usr/bin/git` for every git invocation. Never write a dollar sign followed by a digit anywhere in this file, not even in Perl or awk: Claude Code replaces each such token with a word of the argument before the code is read, which turned `zip <path>` into broken Perl.

**1. Pick what to scan.** `zip <path>` names a release zip, relative to the top of the checkout. Any other argument names a tag or commit to start from. Without one, start from the last release tag:

```bash
cd "$(/usr/bin/git rev-parse --show-toplevel)"
ARG="$ARGUMENTS"; ZIP=""; BASE=""
case "$ARG" in zip\ *) ZIP="${ARG#zip }" ;; *) BASE="$ARG" ;; esac
if [ -z "$ZIP" ]; then
  [ -n "$BASE" ] || BASE="$(/usr/bin/git describe --tags --abbrev=0 --match 'v[0-9]*' HEAD)"
  /usr/bin/git rev-list --count "$BASE"..HEAD
fi
```

Say which starting point it is and how many commits follow it, or which zip.

**2. Build the term list.** This file is public itself, so it never names a sensitive term. They come from three sources, collected into one file of Perl regexes:

- the machine: the main checkout's path, `$HOME`, the host's names, its IPv4 addresses, and every remote host other than `github.com` with its domain;
- `.claude/public-terms.local.txt` in the main checkout, one Perl regex per line, `#` for comments, for what the machine cannot tell: the names of the applications built on the fork, their ticket keys, CI host, job and account names. It is gitignored. When it is missing, say so in the report, because the scan then covers only the other two sources, and offer to create it;
- generic patterns: private IPv4 ranges, LAN-style hostnames, `ssh`/`scp` targets, home directories, VPN names.

```bash
MAIN="$(/usr/bin/git worktree list --porcelain | sed -n '1s/^worktree //p')"
TERMS="$(mktemp)"
{
  cat <<'EOF'
(?<![\d.])(?:10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.\d{1,3}\.\d{1,3}|169\.254\.\d{1,3}\.\d{1,3})(?!\d)
\b[\w-]+\.(?:lan|local|internal|intranet|corp|localdomain|home\.arpa|ts\.net)\b(?![\w.-])
\b(?:ssh|scp|sftp|rsync)\b.*?[\w.-]+@[\w-]+(?:\.[\w-]+)+
/Users/(?!Shared\b)[\w.-]+|/home/[\w.-]+/
\b(?:tail[s]cale|tail[n]et|wire[g]uard|zero[t]ier)\b
EOF
  {
    echo "$MAIN"; echo "$HOME"; hostname -s; hostname
    scutil --get LocalHostName 2>/dev/null; scutil --get ComputerName 2>/dev/null
    ifconfig 2>/dev/null | sed -n 's/^[[:space:]]*inet \([0-9.]*\).*/\1/p' | /usr/bin/grep -v '^127\.'
    /usr/bin/git remote -v | cut -f2 | cut -d' ' -f1 | sed -E 's#^[a-z+]+://##; s#^[^@/]*@##; s#[:/].*$##' \
      | sort -u | /usr/bin/grep -vx 'github.com' | awk -F. '{print} NF>2{print $(NF-1)"."$NF}'
  } | /usr/bin/grep -v '^$' | sort -u | perl -ne 'chomp; print quotemeta($_), "\n"'
  [ -f "$MAIN/.claude/public-terms.local.txt" ] && /usr/bin/grep -vE '^[[:space:]]*(#|$)' "$MAIN/.claude/public-terms.local.txt"
} > "$TERMS"
```

Never print the term list or the machine's values into the conversation beyond the hits themselves.

**3. Scan.** Perl does the matching because macOS's `/usr/bin/grep` has no `-P`, and `git grep -E` there reads `\b` as nothing, so a pattern using it matches no line and the scan passes silently. The log takes no pathspec, because one turns on history simplification, which can leave out the commits of a merged branch; the vendored jars cost nothing, since `-p` prints a binary file without its content.

Without `zip`, the commits since `$BASE` and the uncommitted changes:

```bash
if [ -z "$ZIP" ]; then
# Lines each commit since $BASE adds, then the uncommitted ones, as <sha>|uncommitted <file>:<line>: [<match>] <text>.
# A merge (--cc) shows one prefix column per parent; only a line added against every parent is its own.
{
  /usr/bin/git log -p --cc -U0 --no-color --no-ext-diff --format='%x01%h' "$BASE"..HEAD
  printf '\001uncommitted\n'
  /usr/bin/git diff -U0 --no-color --no-ext-diff HEAD
} | perl -e '
  open my $t, "<", $ARGV[0] or die "$ARGV[0]: $!"; chomp(my @p = <$t>);
  my $re = join "|", map "(?:$_)", grep { length } @p; my ($c, $f, $n, $k);
  while (<STDIN>) {
    if (my ($h) = /^\x01(\S+)/) { ($c, $k) = ($h, undef); next }
    if (/^\+\+\+ /) { ($f) = m{^\+\+\+ b/(.*)}; next }
    if (my ($at, $start) = /^(@@+) (?:-\S+ )+\+(\d+)/) { ($k, $n) = (length($at) - 1, $start); next }
    next unless defined $k && length($_) > $k && substr($_, 0, $k) !~ /[^ +]/;
    my $s = substr($_, $k);
    print "$c $f:$n: [$&] ", substr($s, 0, 200) =~ s/\s+$//r, "\n" if substr($_, 0, $k) eq "+" x $k && $s =~ /$re/i;
    $n++;
  }' "$TERMS" || echo "public-check: the scan did not run (exit $?)"

# Commit messages since $BASE, as commit <sha>: [<match>] <line>
/usr/bin/git log --format='%x00%h%n%B' "$BASE"..HEAD | perl -e '
  open my $t, "<", $ARGV[0] or die "$ARGV[0]: $!"; chomp(my @p = <$t>);
  my $re = join "|", map "(?:$_)", grep { length } @p; local $/ = "\0";
  while (<STDIN>) { chomp; next unless length; my ($h, $b) = split /\n/, $_, 2;
    for (split /\n/, $b // "") { print "commit $h: [$&] ", substr($_, 0, 200), "\n" if /$re/i } }' "$TERMS" || echo "public-check: the scan did not run (exit $?)"
fi
```

With `zip`, what the build generated: every file in the zip, and every entry of a jar in it, whose bytes are not those of a file git tracks. A copy of a tracked file is left out, since its content is in git, and so are the vendored jars, which git tracks whole. That leaves the jars, docs and metadata the build made on this machine. A text file is read by line; anything else, a class file for one, by its printable runs, which is where an absolute path the build embedded would show.

```bash
if [ -n "$ZIP" ]; then
W="$(mktemp -d)"; unzip -q "$ZIP" -d "$W/zip" || echo "public-check: cannot unpack $ZIP"
/usr/bin/git ls-files -s | cut -d' ' -f2 > "$W/tracked"
perl -MDigest::SHA=sha1_hex -MFile::Find -e '
  my ($terms, $tracked, $root, $w) = @ARGV;
  open my $t, "<", $terms or die "$terms: $!"; chomp(my @p = <$t>);
  my $re = join "|", map "(?:$_)", grep { length } @p;
  open my $k, "<", $tracked or die "$tracked: $!"; my %git = map { chomp; ($_ => 1) } <$k>;
  sub slurp { open my $h, "<:raw", $_[0] or return; local $/; scalar <$h> }
  sub tracked { my $d = shift; $git{sha1_hex("blob " . length($d) . "\0" . $d)} }
  sub scan { my ($label, $d) = @_;
    if ($d !~ /\0/) { my $n = 0; for (split /\n/, $d) { $n++; print "$label:$n: [$&] ", substr($_, 0, 160), "\n" if /$re/i } }
    else { for my $s ($d =~ /[\x20-\x7e]{4,}/g) { print "$label: [$&] ", substr($s, 0, 160), "\n" if $s =~ /$re/i } } }
  my (@jars, $files, $entries);
  find({ no_chdir => 1, wanted => sub { return unless -f; my $d = slurp($File::Find::name);
    return if !defined $d || tracked($d); (my $rel = $File::Find::name) =~ s{^\Q$root\E/[^/]+/}{}; $files++;
    if ($rel =~ /\.(?:jar|war|zip)$/) { push @jars, [$rel, $File::Find::name] } else { scan($rel, $d) } } }, $root);
  for my $i (0 .. $#jars) { my ($rel, $f) = @{$jars[$i]}; my $x = "$w/jar$i";
    system("unzip", "-qq", "-o", $f, "-d", $x) == 0 or warn "cannot open $rel\n";
    find({ no_chdir => 1, wanted => sub { return unless -f; my $c = slurp($File::Find::name);
      return if !defined $c || tracked($c); (my $e = $File::Find::name) =~ s{^\Q$x\E/}{}; $entries++; scan("$rel!$e", $c) } }, $x) if -d $x; }
  printf STDERR "generated: %d files, %d jars, %d jar entries scanned\n", $files, scalar @jars, $entries;' "$TERMS" "$W/tracked" "$W/zip" "$W" || echo "public-check: the scan did not run (exit $?)"
rm -rf "$W"
fi
rm -f "$TERMS"
```

**4. Judge and report.** A line starting `public-check:`, or any Perl error, means a scan did not run: that is never a pass, so report it and stop, whatever else was printed. Look at each hit before calling it a finding. A reserved example (`localhost`, `example.com`, `192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`), a Java package (`org.eclipse.jdt.internal.compiler`), a code path (`chrome.storage.local.get`) or upstream test data is not one: list what was dismissed, and why, in one line each. The security contact address in `SECURITY.md` and `CODE_OF_CONDUCT.md` is deliberate. A hit in a commit stays a finding even when a later commit has reworded the line, because the earlier commit is published as it is. Report the findings as a table, *where* (commit and `file:line`, `uncommitted` and `file:line`, or a commit's message) · *what* · *already public?* · *suggested wording*. A commit is already public when `/usr/bin/git merge-base --is-ancestor <sha> github/main` succeeds. With `zip`, *where* is the file in the zip, with its line when it is text, or `<jar>!<entry>`, and nothing there is public yet: `/deploy` uploads the zip only after this passes. No findings: say so, with the starting point and the number of commits scanned, or with the counts the zip scan printed.

**5. Fix, with the user's OK.** Suggest neutral wording ("an application", "this checkout", "the CI host", "a nightly job", "an install owned by root").
- An uncommitted line: reword it, then run this command again.
- A hit in the zip: the build wrote it, so no commit shows it. Find what put it there (a build property, a path a task embeds, a generated file), fix that in git like any other change, rebuild, and scan the zip again.
- A line or a message in a commit not yet on `github/main`: rewording it in a new commit is not enough, because `/deploy` pushes the earlier commit too, and this check keeps reporting it. That commit itself has to change before the push. This rewrites local history, so ask first, and never touch a commit already on `github/main`.
  - The message of `HEAD`: `/usr/bin/git commit --amend`.
  - A line of an older commit that is still there at `HEAD`: reword it, `/usr/bin/git commit --fixup=<sha>`, then `/usr/bin/git rebase --autosquash --rebase-merges github/main`. Git 2.44 and later act on `--autosquash` without `-i`, and `commit.gpgsign` signs every commit the rebase writes. If the rebase stops on a conflict, `/usr/bin/git rebase --abort` and ask. The fixup was already at `HEAD`, so the tree must come out the same: `/usr/bin/git diff --quiet ORIG_HEAD HEAD`. If it differs, the rebase re-created a merge without a change of its own (`--rebase-merges` keeps neither a merge's conflict resolution nor its amendments): put the branch back with `/usr/bin/git reset --hard ORIG_HEAD` and ask.
  - Anything else (a line a later commit already reworded, the message of an older commit): say which commits carry it and ask the user how to rewrite them.
- A commit already on `github/main`: only a history rewrite removes it, and that is not done. Report it and leave it.
