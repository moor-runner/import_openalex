package com.clio.openalex.importer.plan;

public enum Entity {
    SOURCES("sources"),
    AUTHORS("authors");
    private final String name;
    Entity(String name){
        this.name=name;
    }
    private static final String BASE="https://openalex.s3.amazonaws.com/data/jsonl/";
    /**
     * 获取当前entity的s3 manifest.json路径
     * 前置条件：存在的Enum类对象
     * 后置条件：返回这个对象所对应的manifest json路径
     * @return
     */
    public String getLocation(){
        StringBuilder sb=new StringBuilder(BASE);
        sb.append(this.name).append("/manifest.json");
        return sb.toString();
    }

    /**
     * 把用户输入的字符串找到entity对象
     * 前置：字符串，已有的枚举类对象
     * 后置：返回字符串对应的Entity对象
     * 失败契约：
     *      name不存在/不匹配：抛IllegalArgumentException
     * @param name
     * @return Entity对象
     */
    public static Entity parse(String name){
        if(name==null){
            throw new IllegalArgumentException("name为空");
        }
        Entity[] values = values();
        for(int i=0;i<values.length;i++){
            if(name.equalsIgnoreCase(values[i].name)){
                return values[i];
            }
        }
        throw new IllegalArgumentException("没有找到对应name的Entity");
    }
}
