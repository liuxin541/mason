with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscapeHttpServer.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = """    private static void initModel() {
        model.start();
        if (customObstacles != null) {"""

new = """    private static void initModel() {
        System.out.println("[initModel] start...");
        model.start();
        System.out.println("[initModel] model.start done");
        if (customObstacles != null) {"""

content = content.replace(old, new)

old2 = """        syncInsideGridWithObstacles();

        if (customFireSources != null && !customFireSources.isEmpty()) {"""

new2 = """        syncInsideGridWithObstacles();
        System.out.println("[initModel] syncInside done");

        if (customFireSources != null && !customFireSources.isEmpty()) {"""

content = content.replace(old2, new2)

old3 = """        model.initExits();
        model.deadByFire = 0;
        model.deadBySmoke = 0;
        model.schedule.clear();"""

new3 = """        model.initExits();
        model.deadByFire = 0;
        model.deadBySmoke = 0;
        System.out.println("[initModel] before schedule.clear");
        model.schedule.clear();
        System.out.println("[initModel] after schedule.clear");"""

content = content.replace(old3, new3)

old4 = """        model.schedule.scheduleRepeating(new RandomSequence(agents));
        fireSpread = new FireSpread();
        model.schedule.scheduleRepeating(Schedule.EPOCH, 1, fireSpread, 1);
    }"""

new4 = """        System.out.println("[initModel] before schedule persons");
        model.schedule.scheduleRepeating(new RandomSequence(agents));
        fireSpread = new FireSpread();
        model.schedule.scheduleRepeating(Schedule.EPOCH, 1, fireSpread, 1);
        System.out.println("[initModel] done");
    }"""

content = content.replace(old4, new4)

with open(r'D:\mason\src\main\java\sim\app\fireescape\FireEscapeHttpServer.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
