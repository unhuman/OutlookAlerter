#!/bin/bash

# Make a backup of the config file if it doesn't exist
if [ ! -f ~/.outlookalerter/config.properties.bak ]; then
  cp -f ~/.outlookalerter/config.properties ~/.outlookalerter/config.properties.bak
  echo "Created backup of config file at ~/.outlookalerter/config.properties.bak"
fi

# Create a new config with an invalid/expired token but keeping other settings
# First get all non-token settings
grep -v "accessToken" ~/.outlookalerter/config.properties | \
  grep -v "refreshToken" | \
  grep -v "tokenExpiryTime" > ~/.outlookalerter/config.properties.new

# Add invalid token data
echo "accessToken=invalid_token" >> ~/.outlookalerter/config.properties.new
echo "refreshToken=" >> ~/.outlookalerter/config.properties.new
echo "tokenExpiryTime=0" >> ~/.outlookalerter/config.properties.new

# Replace config file
mv ~/.outlookalerter/config.properties.new ~/.outlookalerter/config.properties

echo "Configuration updated with invalid token. Running application now..."

# Run the application in GUI mode
./run-gui.sh

# Note: After testing, you can restore the original config with:
# cp -f ~/.outlookalerter/config.properties.bak ~/.outlookalerter/config.properties
