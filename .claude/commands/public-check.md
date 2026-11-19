---
name: public-check
description: Search what the next /deploy will publish (every commit since the last release tag, its added lines and its message, and the uncommitted changes) for anything that identifies the local environment. Reports; changes nothing without the user's OK.
category: Maintenance
tags: [release, privacy, audit]
argument-hint: "[empty | <tag or commit to start from>]"
---

Everything `/deploy` pushes is public: `github` is a public mirror, and so are the release zip and its notes. This command searches only what the next `/deploy` will add, never the whole repository: the lines each commit since the last release tag adds (for a merge, the lines it adds itself), the uncommitted changes to tracked files, and the commit messages. It reads every commit on its own, not the difference between the tag and today, because `/deploy` pushes each of those commits: a line that one commit adds and a later one removes is still published. AGENTS.md's "Public Repository" section is the rule it checks. It reports and changes nothing until the user agrees. Use `/usr/bin/git` for every git invocation.

**1. Pick the starting point.** The argument, when given, names a tag or commit. Otherwise take the last release tag:

```bash
cd "$(/usr/bin/git rev-parse --show-toplevel)"
BASE="$ARGUMENTS"
[ -n "$BASE" ] || BASE="$(/usr/bin/git describe --tags --abbrev=0 --match 'v[0-9]*' HEAD)"
/usr/bin/git rev-list --count "$BASE"..HEAD
```

Say which starting point it is and how many commits follow it.

**2. Build the term list.** This file is public itself, so it never names a sensitive term. They come from three sources, collected into one file of Perl regexes:

- the machine: the main checkout's path, `$HOME`, the host's names, its IPv4 addresses, and every remote host other than `github.com` with its domain;
- `.claude/public-terms.local.txt` in the main checkout, one Perl regex per line, `#` for comments, for what the machine cannot tell: the names of the applications built on the fork, their ticket keys, CI host, job and account names. It is gitignored. When it is missing, say so in the report, because the scan then covers only the other two sources, and offer to create it;
- generic patterns: private IPv4 ranges, LAN-style hostnames, `ssh`/`scp` targets, home directories, VPN names.

```bash
MAIN="$(/usr/bin/git worktree list --porcelain | awk 'NR==1{print $2}')"
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
    ifconfig 2>/dev/null | awk '/inet /{print $2}' | /usr/bin/grep -v '^127\.'
    /usr/bin/git remote -v | awk '{print $2}' | sed -E 's#^[a-z+]+://##; s#^[^@/]*@##; s#[:/].*$##' \
      | sort -u | /usr/bin/grep -vx 'github.com' | awk -F. '{print} NF>2{print $(NF-1)"."$NF}'
  } | /usr/bin/grep -v '^$' | sort -u | perl -ne 'chomp; print quotemeta($_), "\n"'
  [ -f "$MAIN/.claude/public-terms.local.txt" ] && /usr/bin/grep -vE '^[[:space:]]*(#|$)' "$MAIN/.claude/public-terms.local.txt"
} > "$TERMS"
```

Never print the term list or the machine's values into the conversation beyond the hits themselves.

**3. Scan.** Perl does the matching because macOS's `/usr/bin/grep` has no `-P`, and `git grep -E` there reads `\b` as nothing, so a pattern using it matches no line and the scan passes silently. The log takes no pathspec, because one turns on history simplification, which can leave out the commits of a merged branch; the vendored jars cost nothing, since `-p` prints a binary file without its content.

```bash
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
    if (/^\x01(\S+)/) { ($c, $k) = ($1, undef); next }
    if (m{^\+\+\+ (?:b/(.*)|/dev/null)}) { $f = $1; next }
    if (/^(@@+) (?:-\S+ )+\+(\d+)/) { ($k, $n) = (length($1) - 1, $2); next }
    next unless defined $k && length($_) > $k && substr($_, 0, $k) !~ /[^ +]/;
    my $s = substr($_, $k);
    print "$c $f:$n: [$&] ", substr($s, 0, 200) =~ s/\s+$//r, "\n" if substr($_, 0, $k) eq "+" x $k && $s =~ /$re/i;
    $n++;
  }' "$TERMS"

# Commit messages since $BASE, as commit <sha>: [<match>] <line>
/usr/bin/git log --format='%x00%h%n%B' "$BASE"..HEAD | perl -e '
  open my $t, "<", $ARGV[0] or die "$ARGV[0]: $!"; chomp(my @p = <$t>);
  my $re = join "|", map "(?:$_)", grep { length } @p; local $/ = "\0";
  while (<STDIN>) { chomp; next unless length; my ($h, $b) = split /\n/, $_, 2;
    for (split /\n/, $b // "") { print "commit $h: [$&] ", substr($_, 0, 200), "\n" if /$re/i } }' "$TERMS"
rm -f "$TERMS"
```

**4. Judge and report.** Look at each hit before calling it a finding. A reserved example (`localhost`, `example.com`, `192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`), a Java package (`org.eclipse.jdt.internal.compiler`), a code path (`chrome.storage.local.get`) or upstream test data is not one: list what was dismissed, and why, in one line each. The security contact address in `SECURITY.md` and `CODE_OF_CONDUCT.md` is deliberate. A hit in a commit stays a finding even when a later commit has reworded the line, because the earlier commit is published as it is. Report the findings as a table, *where* (commit and `file:line`, `uncommitted` and `file:line`, or a commit's message) · *what* · *already public?* · *suggested wording*. A commit is already public when `/usr/bin/git merge-base --is-ancestor <sha> github/main` succeeds. No findings: say so, with the starting point and the number of commits scanned.

**5. Fix, with the user's OK.** Suggest neutral wording ("an application", "this checkout", "the CI host", "a nightly job", "an install owned by root").
- An uncommitted line: reword it, then run this command again.
- A line or a message in a commit not yet on `github/main`: rewording it in a new commit is not enough, because `/deploy` pushes the earlier commit too, and this check keeps reporting it. That commit itself has to change before the push. This rewrites local history, so ask first, and never touch a commit already on `github/main`.
  - The message of `HEAD`: `/usr/bin/git commit --amend`.
  - A line of an older commit that is still there at `HEAD`: reword it, `/usr/bin/git commit --fixup=<sha>`, then `/usr/bin/git rebase --autosquash --rebase-merges github/main`. Git 2.44 and later act on `--autosquash` without `-i`, and `commit.gpgsign` signs every commit the rebase writes. If the rebase stops on a conflict, `/usr/bin/git rebase --abort` and ask. The fixup was already at `HEAD`, so the tree must come out the same: `/usr/bin/git diff --quiet ORIG_HEAD HEAD`. If it differs, the rebase re-created a merge without a change of its own (`--rebase-merges` keeps neither a merge's conflict resolution nor its amendments): put the branch back with `/usr/bin/git reset --hard ORIG_HEAD` and ask.
  - Anything else (a line a later commit already reworded, the message of an older commit): say which commits carry it and ask the user how to rewrite them.
- A commit already on `github/main`: only a history rewrite removes it, and that is not done. Report it and leave it.
