with open(r'D:\mason\src\main\java\sim\app\fireescape\Person.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = """    @Override
    public void step(SimState state) {
        FireEscape fm = (FireEscape) state;
        if (!fm.showPersons) return;
        if (!alive || escaped) return;"""

new = """    @Override
    public void step(SimState state) {
        FireEscape fm = (FireEscape) state;
        if (System.currentTimeMillis() % 2000 < 100)
            System.out.println("[Person step] showPersons=" + fm.showPersons + " alive=" + alive + " escaped=" + escaped + " pos=(" + x + "," + y + "," + z + ")");
        if (!fm.showPersons) return;
        if (!alive || escaped) return;"""

content = content.replace(old, new)

with open(r'D:\mason\src\main\java\sim\app\fireescape\Person.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('done')
