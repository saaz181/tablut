
### Initialization of Game

The easiest way is to run the project is by **ANT**.

Go into the project folder (the folder with the `build.xml` file):
```
cd TablutCompetition/Tablut
```

Compile the project:

```
ant clean
ant compile
```

The compiled project is in  the `build` folder.
Run the server with:

```
ant server
```
And if you want to see the GUI run:
```
ant gui-server
```


If you want to clean, compile and run the server with one-line command:
```
ant clean && ant compile && ant server
```
For **GUI** just use:
```
ant clean && ant compile && ant gui-server
```

---
### Playing with AI

To start an AI player, you should run:
```
ant myaiwhite
```
to play as white, and:
```
ant myaiblack
```
to play as black.



You can choose your opponent to be either **black** | **white**:
```
ant randomwhite

ant randomblack
```