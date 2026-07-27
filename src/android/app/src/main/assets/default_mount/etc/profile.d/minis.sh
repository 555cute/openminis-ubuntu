# MinisApp shell configuration (Ubuntu Agent)
# Loaded by /etc/profile via the profile.d mechanism (login shells only).

# Prompt parity with OpenMinis: root@minis:/var/minis#
export PS1='\u@minis:\w\$ '

# bash history
export HISTFILE="$HOME/.bash_history"
export HISTSIZE=1000
export HISTCONTROL=ignoredups

# Default pager
export PAGER=less

# URL interception
export BROWSER=/usr/local/bin/minis-open

# Noninteractive apt by default inside the agent sandbox
export DEBIAN_FRONTEND=noninteractive

# T222: PRoot's link2symlink extension creates .l2s.* sentinel files alongside
# hardlink targets; hide them from normal listings.
export FIGNORE=".l2s"

# Ensure /opt/bin and /usr/local/bin are on PATH
case ":$PATH:" in
  *:/opt/bin:*) ;;
  *) export PATH="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/bin" ;;
esac
