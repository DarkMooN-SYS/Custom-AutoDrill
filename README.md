# Auto Drill

[![GitHub all releases](https://img.shields.io/github/downloads/DarkMooN-SYS/Custom-AutoDrill/total)](https://github.com/DarkMooN-SYS/Custom-AutoDrill/releases)
![GitHub Repo stars](https://img.shields.io/github/stars/DarkMooN-SYS/Custom-AutoDrill?style=social)

Mindustry Mod which adds tools to automatically fill a resource patch with drills.

Enable the mod by the activation key (default `H`) or by clicking the activation button next to the minimap.
When activated, clicking on any tile will display a toolbar with the drills the current tile can be mined with.
The mechanical, pneumatic, laser, and airblast drill buttons fill the resource patch with drills and a bridge layout facing in the picked direction. Other registered drills, including drills loaded by server mods, are detected automatically and can be placed with the optimizer.

Laser and airblast drills also use the bridge planner. Custom drills loaded into the game's content registry are detected automatically and can be placed with the optimizer.

For Erekir, the mod can automatically fill wall resources with beam drills.

The mod has several configuration options.

- **Activation Key** 
  
  The key with which the mod is toggled

- **Display Toggle Button**
  
  Whether to display the toggle button
  
- **Max Tiles** 
  
  The maximum number of tiles that are flood filled and later filled with drills, highly effects performance

- **Minimum Ores** 
  
  The minimum amount of resource tiles any drill must cover

- **Optimization Quality** 
  
  The optimization algorithm for filling an area with drills iterates the whole possibility space, starting with tiles which yield the highest mining rate. The optimization quality parameter determines how early this search is terminated. Increasing this number may produce better results in trade of computation speed.

- **Place Water Extractors and Power Nodes** 
  
  Whether to place water extractors and power nodes next to the drills

