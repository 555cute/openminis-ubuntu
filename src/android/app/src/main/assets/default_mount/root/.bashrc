# Interactive bash configuration for OpenMinis Ubuntu Agent
# Sourced for interactive non-login shells when BASH_ENV / bash -i is used.

alias ll='ls -alF'
alias la='ls -A'
alias l='ls -CF'
alias ..='cd ..'
alias ...='cd ../..'

# Keep ash history file name compatibility if users still have .ashrc habits
[ -f "$HOME/.ashrc" ] && . "$HOME/.ashrc"
