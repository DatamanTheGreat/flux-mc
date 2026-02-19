# Main Overview

Flux-mc is a Minecraft Paper Velocity plugin that allows for "serverless scaling" of Minecraft server instances for small playerbases. 
A player will login to the Velocity server and instantly get kicked out with a message saying "Wait for server to start." 
In that time, flux-mc will spin up a Vultr VX1 instance of the owner's choosing. 
Once the server boots up, the player can then reconnect and join as if they were directly connecting to the VX1 instance.
Once all players are logged off, a timer begins and then the VX1 instance is destroyed.
This plugin is for people who want a cheap but performant Minecraft server for that "Minecraft phase." 
The only persistant server that has to exist is the Velocity server and that can run on Vultr's cheapest VPS.

# Why Vultr VX1?

Vultr VX1 instances use a block volume to boot off of so world saves and configurations are saved between instances being created and destroyed. 
VX1 instances are also use dedicated, not shared CPUs so performance is extreme.
You are only billed on the time that the server exists.
Since the server only exists when the players are online, if you have a small playerbase you can have a high-performance server for a low cost.

# Instructions

Simply drop the flux-mc.jar in the velocity server's /plugins folder and run the server.
You can change configuration settings in the config.toml file.
You'll need a Vultr API key with the proper permissions to create and destroy instances.
The Vultr API has the session titles for each VX1 instance. You'll also need a persistent block volume.
