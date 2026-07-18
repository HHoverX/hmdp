local key = KEYS[1]--锁的key
local threadId = ARGV[1]--线程id
local releaseTime = ARGV[2]--过期时间
--比较线程标识与锁的标识是否相同
if(redis.call("hexist",key,threadId) == 0)then
    --锁已释放
    return nil
end
--锁计数器-1
local count = redis.call("hincrby",key,threadId,-1)
--判断锁计数器是否为0
if(count > 0)then
    --重置锁有效期
    redis.call("expire",key,releaseTime)
    return nil
else
    --释放锁
    redis.call("del",key)
    return nil
end