local key = KEYS[1]--锁的key
local threadId = ARGV[1]--线程id
local releaseTime = ARGV[2]--过期时间
--判断锁是否存在
if(redis.call("exist",key) == 0)then
    --获取锁并添加线程标识
    redis.call("hset",key,threadId,0);
    --设置锁有效期
    redis.call("expire",key,releaseTime);
    --返回结果
    return 1
end
--判断锁标识是否是自己
if(redis.call("hexist",key,threadId) == 1)then
    --锁计数加1
    redis.call("hincrby",key,threadId,'1')
    --重置锁有效期
    redis.call("expire",key,releaseTime)
    --返回结果
    return 1
end
--不是自己的锁，获取锁失败
return 0
